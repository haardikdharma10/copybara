/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.copybara.GeneralOptions.FORCE;
import static com.google.copybara.LazyResourceLoader.memoized;
import static com.google.copybara.exception.ValidationException.checkCondition;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.Change;
import com.google.copybara.ChangeMessage;
import com.google.copybara.Destination;
import com.google.copybara.DestinationEffect;
import com.google.copybara.DestinationStatusVisitor;
import com.google.copybara.Endpoint;
import com.google.copybara.GeneralOptions;
import com.google.copybara.LabelFinder;
import com.google.copybara.LazyResourceLoader;
import com.google.copybara.Revision;
import com.google.copybara.TransformResult;
import com.google.copybara.WriterContext;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.ChangeRejectedException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.GitDestination.WriterImpl.WriteHook;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.util.DiffUtil;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Console;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.io.IOException;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A Git repository destination.
 */
public final class GitDestination implements Destination<GitRevision> {

  private static final String ORIGIN_LABEL_SEPARATOR = ": ";

  static class MessageInfo {
    final ImmutableList<LabelFinder> labelsToAdd;

    MessageInfo(ImmutableList<LabelFinder> labelsToAdd) {
      this.labelsToAdd = checkNotNull(labelsToAdd);
    }
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String repoUrl;
  private final String fetch;
  private final String push;
  private final GitDestinationOptions destinationOptions;
  private final GitOptions gitOptions;
  private final GeneralOptions generalOptions;
  // Whether the skip_push flag is set in copy.bara.sky
  private final boolean skipPushDEPRECATED;

  private final Iterable<GitIntegrateChanges> integrates;
  // Whether skip_push is set, either by command line or copy.bara.sky
  private final boolean effectiveSkipPushDEPRECATED;
  private final WriteHook writerHook;
  private final LazyResourceLoader<GitRepository> localRepo;

  GitDestination(
      String repoUrl,
      String fetch,
      String push,
      GitDestinationOptions destinationOptions,
      GitOptions gitOptions,
      GeneralOptions generalOptions,
      boolean skipPush,
      WriteHook writerHook,
      Iterable<GitIntegrateChanges> integrates) {
    this.repoUrl = checkNotNull(repoUrl);
    this.fetch = checkNotNull(fetch);
    this.push = checkNotNull(push);
    this.destinationOptions = checkNotNull(destinationOptions);
    this.gitOptions = Preconditions.checkNotNull(gitOptions);
    this.generalOptions = Preconditions.checkNotNull(generalOptions);
    this.skipPushDEPRECATED = skipPush;
    this.integrates = Preconditions.checkNotNull(integrates);
    this.effectiveSkipPushDEPRECATED = skipPush || destinationOptions.skipPush;
    this.writerHook = checkNotNull(writerHook);
    this.localRepo = memoized(ignored -> destinationOptions.localGitRepo(repoUrl));
  }

  /**
   * Throws an exception if the user.email or user.name Git configuration settings are not set. This
   * helps ensure that the committer field of generated commits is correct.
   */
  private static void verifyUserInfoConfigured(GitRepository repo)
      throws RepoException, ValidationException {
    String output = repo.simpleCommand("config", "-l").getStdout();
    boolean nameConfigured = false;
    boolean emailConfigured = false;
    for (String line : output.split("\n")) {
      if (line.startsWith("user.name=")) {
        nameConfigured = true;
      } else if (line.startsWith("user.email=")) {
        emailConfigured = true;
      }
    }
    checkCondition(nameConfigured && emailConfigured,
        "'user.name' and/or 'user.email' are not configured. Please run "
            + "`git config --global SETTING VALUE` to set them");
  }

  @Override
  public Writer<GitRevision> newWriter(WriterContext writerContext) {

    boolean effectiveSkipPush = GitDestination.this.effectiveSkipPushDEPRECATED 
        || writerContext.isDryRun();

    WriterState state = new WriterState(
              localRepo, destinationOptions.getLocalBranch(push, writerContext.isDryRun()));

    return new WriterImpl<>(
        effectiveSkipPush,
        repoUrl,
        fetch,
        push,
        generalOptions,
        writerHook,
        state,
        destinationOptions.nonFastForwardPush,
        integrates,
        destinationOptions.lastRevFirstParent,
        destinationOptions.ignoreIntegrationErrors,
        destinationOptions.localRepoPath,
        destinationOptions.committerName,
        destinationOptions.committerEmail,
        destinationOptions.rebaseWhenBaseline(),
        gitOptions.visitChangePageSize);
  }

  /**
   * State to be maintained between writer instances.
   */
  static class WriterState {

    boolean alreadyFetched;
    boolean firstWrite = true;
    final LazyResourceLoader<GitRepository> localRepo;
    final String localBranch;

    WriterState(LazyResourceLoader<GitRepository> localRepo, String localBranch) {
      this.localRepo = localRepo;
      this.localBranch = localBranch;
    }
  }

  /**
   * A writer for git.*destination destinations. Note that this is not a public interface and
   * shouldn't be used directly.
   */
  public static class WriterImpl<S extends WriterState>
      implements Writer<GitRevision> {

    final boolean skipPush;
    private final String repoUrl;
    private final String remoteFetch;
    private final String remotePush;
    private final boolean force;
    // Only use this console when you don't receive one as a parameter.
    private final Console baseConsole;
    private final GeneralOptions generalOptions;
    private final WriteHook writeHook;
    final S state;
    // We could get it from destinationOptions but this is in preparation of a GH PR destination.
    private final boolean nonFastForwardPush;
    private final Iterable<GitIntegrateChanges> integrates;
    private final boolean lastRevFirstParent;
    private final boolean ignoreIntegrationErrors;
    private final String localRepoPath;
    private final String committerName;
    private final String committerEmail;
    private final boolean rebase;
    private final int visitChangePageSize;

    /**
     * Create a new git.destination writer
     */
    WriterImpl(boolean skipPush, String repoUrl, String remoteFetch,
        String remotePush, GeneralOptions generalOptions, WriteHook writeHook,
        S state, boolean nonFastForwardPush, Iterable<GitIntegrateChanges> integrates,
        boolean lastRevFirstParent, boolean ignoreIntegrationErrors, String localRepoPath,
        String committerName, String committerEmail, boolean rebase, int visitChangePageSize) {
      this.skipPush = skipPush;
      this.repoUrl = checkNotNull(repoUrl);
      this.remoteFetch = checkNotNull(remoteFetch);
      this.remotePush = checkNotNull(remotePush);
      this.force = generalOptions.isForced();
      this.baseConsole = checkNotNull(generalOptions.console());
      this.generalOptions = generalOptions;
      this.writeHook = checkNotNull(writeHook);
      this.state = checkNotNull(state);
      this.nonFastForwardPush = nonFastForwardPush;
      this.integrates = Preconditions.checkNotNull(integrates);
      this.lastRevFirstParent = lastRevFirstParent;
      this.ignoreIntegrationErrors = ignoreIntegrationErrors;
      this.localRepoPath = localRepoPath;
      this.committerName = committerName;
      this.committerEmail = committerEmail;
      this.rebase = rebase;
      this.visitChangePageSize = visitChangePageSize;
    }

    @Override
    public void visitChanges(@Nullable GitRevision start, ChangesVisitor visitor)
        throws RepoException, ValidationException {
      GitRepository repository = getRepository(baseConsole);
      try {
        fetchIfNeeded(repository, baseConsole);
      } catch (ValidationException e) {
        throw new CannotResolveRevisionException(
            "Cannot visit changes because fetch failed. Does the destination branch exist?", e);
      }
      GitRevision startRef = getLocalBranchRevision(repository);
      if (startRef == null) {
        return;
      }
      ChangeReader.Builder queryChanges =
          ChangeReader.Builder.forDestination(repository, baseConsole)
              .setVerbose(generalOptions.isVerbose());

      GitVisitorUtil.visitChanges(
          start == null ? startRef : start,
          visitor,
          queryChanges,
          generalOptions,
          "destination",
          visitChangePageSize);
    }

    /**
     * Do a fetch iff we haven't done one already. Prevents doing unnecessary fetches.
     */
    private void fetchIfNeeded(GitRepository repo, Console console)
        throws RepoException, ValidationException {
      if (!state.alreadyFetched) {
        GitRevision revision = fetchFromRemote(console, repo, repoUrl, remoteFetch);
        if (revision != null) {
          repo.simpleCommand("branch", state.localBranch, revision.getSha1());
        }
        state.alreadyFetched = true;
      }
    }

    @Nullable
    @Override
    public DestinationStatus getDestinationStatus(Glob destinationFiles, String labelName)
        throws RepoException, ValidationException {
      GitRepository repo = getRepository(baseConsole);
      try {
        fetchIfNeeded(repo, baseConsole);
      } catch (ValidationException e) {
        return null;
      }
      GitRevision startRef = getLocalBranchRevision(repo);
      if (startRef == null) {
        return null;
      }

      PathMatcher pathMatcher = destinationFiles.relativeTo(Paths.get(""));
      DestinationStatusVisitor visitor = new DestinationStatusVisitor(pathMatcher, labelName);
      ChangeReader.Builder changeReader =
          ChangeReader.Builder.forDestination(repo, baseConsole)
              .setVerbose(generalOptions.isVerbose())
              .setFirstParent(lastRevFirstParent)
              .grep("^" + labelName + ORIGIN_LABEL_SEPARATOR);
      try {
        // Using same visitChangePageSize for now
        GitVisitorUtil.visitChanges(
            startRef,
            visitor,
            changeReader,
            generalOptions,
            "get_destination_status",
            visitChangePageSize);
      } catch (CannotResolveRevisionException e) {
        // TODO: handle
        return null;
      }
      return visitor.getDestinationStatus();
    }

    @Override
    public Endpoint getFeedbackEndPoint(Console console) throws ValidationException {
      return writeHook.getFeedbackEndPoint(console);
    }

    @Nullable
    private GitRevision getLocalBranchRevision(GitRepository gitRepository) throws RepoException {
      try {
        return gitRepository.resolveReference(state.localBranch);
      } catch (CannotResolveRevisionException e) {
        if (force) {
          return null;
        }
        throw new RepoException(String.format("Could not find %s in %s and '%s' was not used",
            remoteFetch, repoUrl, GeneralOptions.FORCE));
      }
    }

    @Override
    public boolean supportsHistory() {
      return true;
    }

    /**
     * A write hook allows us to customize the behavior or git.destination writer for other
     * implementations.
     */
    public interface WriteHook {

      /** Customize the writer for a particular destination. */
      MessageInfo generateMessageInfo(TransformResult transformResult)
          throws ValidationException, RepoException;
      
      /**
       * Validate or do modifications to the current change to be pushed.
       *
       * <p>{@code HEAD} commit should point to the commit to be pushed. Any change on the local
       * git repo should keep current commit as HEAD or do the proper modifications to make HEAD to
       * point to a new/modified changes(s).
       */
      default void beforePush(GitRepository repo, MessageInfo messageInfo, boolean skipPush,
          List<? extends Change<?>> originChanges) throws RepoException, ValidationException { }

      /**
       * Construct the reference to push based on the pushToRefsFor reference. Implementations of
       * this method can change the reference to a different reference.
       */
      String getPushReference(String pushToRefsFor, TransformResult transformResult);

      /**
       * Process the server response from the push command and compute the effects that happened
       */
      ImmutableList<DestinationEffect> afterPush(String serverResponse, MessageInfo messageInfo,
          GitRevision pushedRevision, List<? extends Change<?>> originChanges);

      default Endpoint getFeedbackEndPoint(Console console) throws ValidationException {
        return Endpoint.NOOP_ENDPOINT;
      }

      default ImmutableSetMultimap<String, String> describe() {
        return ImmutableSetMultimap.of();
      }
    }

    /**
     * A Write hook for standard git repositories
     */
    public static class DefaultWriteHook implements WriteHook {

      @Override
      public MessageInfo generateMessageInfo(TransformResult transformResult) {
          Revision rev = transformResult.getCurrentRevision();
          return new MessageInfo(
              transformResult.isSetRevId()
                  ? ImmutableList.of(new LabelFinder(
                  rev.getLabelName() + ORIGIN_LABEL_SEPARATOR + rev.asString()))
                  : ImmutableList.of());
      }

      @Override
      public ImmutableList<DestinationEffect> afterPush(String serverResponse,
          MessageInfo messageInfo, GitRevision pushedRevision,
          List<? extends Change<?>> originChanges) {
        return ImmutableList.of(
            new DestinationEffect(
                DestinationEffect.Type.CREATED,
                String.format("Created revision %s", pushedRevision.getSha1()),
                originChanges,
                new DestinationEffect.DestinationRef(
                    pushedRevision.getSha1(), "commit", /*url=*/ null)));
      }

      @Override
      public String getPushReference(String pushToRefsFor, TransformResult transformResult) {
        return pushToRefsFor;
      }
    }

    @Override
    public ImmutableList<DestinationEffect> write(TransformResult transformResult,
        Glob destinationFiles, Console console)
        throws ValidationException, RepoException, IOException {
      logger.atInfo().log("Exporting from %s to: %s", transformResult.getPath(), this);
      String baseline = transformResult.getBaseline();

      GitRepository scratchClone = getRepository(console);

      fetchIfNeeded(scratchClone, console);

      console.progress("Git Destination: Checking out " + remoteFetch);

      GitRevision localBranchRevision = getLocalBranchRevision(scratchClone);
      updateLocalBranchToBaseline(scratchClone, baseline);

      if (state.firstWrite) {
        String reference = baseline != null ? baseline : state.localBranch;
        configForPush(getRepository(console), repoUrl, remotePush);
        if (!force && localBranchRevision == null) {
          throw new RepoException(String.format(
              "Cannot checkout '%s' from '%s'. Use '%s' if the destination is a new git repo or"
                  + " you don't care about the destination current status", reference,
              repoUrl,
              GeneralOptions.FORCE));
        }
        if (localBranchRevision != null) {
          scratchClone.simpleCommand("checkout", "-f", "-q", reference);
        } else {
          // Configure the commit to go to local branch instead of master.
          scratchClone.simpleCommand("symbolic-ref", "HEAD", getCompleteRef(state.localBranch));
        }
        state.firstWrite = false;
      } else if (!skipPush) {
        // Should be a no-op, but an iterative migration could take several minutes between
        // migrations so lets fetch the latest first.
        fetchFromRemote(console, scratchClone, repoUrl, remoteFetch);
      }

      PathMatcher pathMatcher = destinationFiles.relativeTo(scratchClone.getWorkTree());
      // Get the submodules before we stage them for deletion with
      // repo.simpleCommand(add --all)
      AddExcludedFilesToIndex excludedAdder =
          new AddExcludedFilesToIndex(scratchClone, pathMatcher);
      excludedAdder.findSubmodules(console);

      GitRepository alternate = scratchClone.withWorkTree(transformResult.getPath());

      console.progress("Git Destination: Adding all files");
      alternate.add().force().all().run();

      console.progress("Git Destination: Excluding files");
      excludedAdder.add();

      console.progress("Git Destination: Creating a local commit");
      MessageInfo messageInfo = writeHook.generateMessageInfo(transformResult);

      ChangeMessage msg = ChangeMessage.parseMessage(transformResult.getSummary());
      for (LabelFinder label : messageInfo.labelsToAdd) {
        msg = msg.withNewOrReplacedLabel(label.getName(), label.getSeparator(), label.getValue());
      }

      String commitMessage = msg.toString();
      alternate.commit(
          transformResult.getAuthor().toString(),
          transformResult.getTimestamp(),
          commitMessage);

      for (GitIntegrateChanges integrate : integrates) {
        integrate.run(alternate, generalOptions, messageInfo,
            path -> !pathMatcher.matches(scratchClone.getWorkTree().resolve(path)),
            transformResult, ignoreIntegrationErrors);
      }

      // Don't leave unstaged/untracked files in the work-tree. This is a problem for rebase
      // and in general any inspection of the directory after Copybara execution.
      // Clean unstaged:
      scratchClone.simpleCommand("reset", "--hard");
      // ...and untracked ones:
      scratchClone.simpleCommand("clean", "-f");

      if (baseline != null && rebase) {
        // Note that it is a different work-tree from the previous reset
        alternate.simpleCommand("reset", "--hard");
        alternate.rebase(localBranchRevision.getSha1());
      }

      if (localRepoPath != null) {
        scratchClone.simpleCommand("checkout", state.localBranch);
      }

      if (transformResult.isAskForConfirmation()) {
        // The git repo contains the staged changes at this point. Git diff writes to Stdout
        console.info(DiffUtil.colorize(
            console, scratchClone.simpleCommand("show", "HEAD").getStdout()));
        if (!console.promptConfirmation(
            String.format("Proceed with push to %s %s?", repoUrl, remotePush))) {
          console.warn("Migration aborted by user.");
          throw new ChangeRejectedException(
              "User aborted execution: did not confirm diff changes.");
        }
      }

      GitRevision head = scratchClone.resolveReference("HEAD");
      SkylarkList<? extends Change<?>> originChanges = transformResult.getChanges().getCurrent();
      // BeforePush will update existing PRs in github if skip push is not true
      writeHook.beforePush(scratchClone, messageInfo, skipPush, originChanges);
      if (skipPush) {
        console.infoFmt(
            "Git Destination: skipped push to remote. Check the local commits at %s",
            scratchClone.getGitDir());
        return ImmutableList.of(
            new DestinationEffect(
                DestinationEffect.Type.CREATED,
                String.format(
                    "Dry run commit '%s' created locally at %s", head, scratchClone.getGitDir()),
                originChanges,
                new DestinationEffect.DestinationRef(head.getSha1(), "commit", /*url=*/ null)));
      }
      String push = writeHook.getPushReference(getCompleteRef(remotePush), transformResult);
      console.progress(String.format("Git Destination: Pushing to %s %s", repoUrl, push));
      checkCondition(!nonFastForwardPush
          || !Objects.equals(remoteFetch, remotePush), "non fast-forward push is only"
          + " allowed when fetch != push");

      String serverResponse = generalOptions.repoTask(
          "push",
          () -> scratchClone.push()
              .withRefspecs(repoUrl, ImmutableList.of(scratchClone.createRefSpec(
                  (nonFastForwardPush ? "+" : "") + "HEAD:" + push)))
              .run()
      );
      return writeHook.afterPush(serverResponse, messageInfo, head, originChanges);
    }

    /**
     * Get the local {@link GitRepository} associated with the writer.
     *
     * Note that this is not a public interface and is subjec to change.
     */
    public GitRepository getRepository(Console console) throws RepoException, ValidationException {
      return state.localRepo.load(console);
    }

    private void updateLocalBranchToBaseline(GitRepository repo, String baseline)
        throws RepoException {
      if (baseline != null && !repo.refExists(baseline)) {
        throw new RepoException("Cannot find baseline '" + baseline
            + (getLocalBranchRevision(repo) != null
               ? "' from fetch reference '" + remoteFetch + "'"
               : "' and fetch reference '" + remoteFetch + "' itself")
            + " in " + repoUrl + ".");
      } else if (baseline != null) {
        // Update the local branch to use the baseline
        repo.simpleCommand("update-ref", state.localBranch, baseline);
      }
    }

    @Nullable
    private GitRevision fetchFromRemote(Console console, GitRepository repo, String repoUrl,
        String fetch) throws RepoException, ValidationException {
      String completeFetchRef = getCompleteRef(fetch);
      try (ProfilerTask ignore = generalOptions.profiler().start("destination_fetch")){
        console.progress("Git Destination: Fetching: " + repoUrl + " " + completeFetchRef);
        return repo.fetchSingleRef(repoUrl, completeFetchRef);
      } catch (CannotResolveRevisionException e) {
        String warning = String.format("Git Destination: '%s' doesn't exist in '%s'",
            completeFetchRef, repoUrl);
        if (!force) {
          throw new ValidationException(
              "%s. Use %s flag if you want to push anyway", warning, FORCE);
        }
        console.warn(warning);
      }
      return null;
    }

    private String getCompleteRef(String fetch) {
      // Assume that it is a branch. Doesn't work for tags. But we don't update tags (For now).
      return fetch.startsWith("refs/") ? fetch : "refs/heads/" + fetch;
    }

    private void configForPush(GitRepository repo, String repoUrl, String push)
        throws RepoException, ValidationException {

      if (localRepoPath != null) {
        // Configure the local repo to allow pushing to the ref manually outside of Copybara
        repo.simpleCommand("config", "remote.copybara_remote.url", repoUrl);
        repo.simpleCommand("config", "remote.copybara_remote.push",
            state.localBranch + ":" + push);
        repo.simpleCommand("config", "branch." + state.localBranch
            + ".remote", "copybara_remote");
      }
      if (!Strings.isNullOrEmpty(committerName)) {
        repo.simpleCommand("config", "user.name", committerName);
      }
      if (!Strings.isNullOrEmpty(committerEmail)) {
        repo.simpleCommand("config", "user.email", committerEmail);
      }
      verifyUserInfoConfigured(repo);

    }
  }

  @VisibleForTesting
  String getFetch() {
    return fetch;
  }

  @VisibleForTesting
  String getPush() {
    return push;
  }

  @Override
  public String getLabelNameWhenOrigin() {
    return GitRepository.GIT_ORIGIN_REV_ID;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("repoUrl", repoUrl)
        .add("fetch", fetch)
        .add("push", push)
        .add("skip_push", skipPushDEPRECATED)
        .toString();
  }

  /**
   * Not a public API. It is subject to change.
   */
  public LazyResourceLoader<GitRepository> getLocalRepo() {
    return localRepo;
  }

  @Override
  public String getType() {
    return "git.destination";
  }

  @Override
  public ImmutableSetMultimap<String, String> describe(Glob originFiles) {
    ImmutableSetMultimap.Builder<String, String> builder =
        new ImmutableSetMultimap.Builder<String, String>()
            .put("type", getType())
            .put("url", repoUrl)
            .put("fetch", fetch)
            .put("push", push);
    if (skipPushDEPRECATED) {
      builder.put("skip_push", "" + skipPushDEPRECATED);
    }
    builder.putAll(writerHook.describe());
    return builder.build();
  }

}
