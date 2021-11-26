package jetbrains.buildServer.buildTriggers.vcs.git.command;

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import jetbrains.buildServer.buildTriggers.vcs.git.FetchCommand;
import jetbrains.buildServer.buildTriggers.vcs.git.LsRemoteCommand;
import jetbrains.buildServer.buildTriggers.vcs.git.PushCommand;
import jetbrains.buildServer.buildTriggers.vcs.git.TagCommand;
import jetbrains.buildServer.buildTriggers.vcs.git.*;
import jetbrains.buildServer.buildTriggers.vcs.git.command.impl.GitFacadeImpl;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.ssh.VcsRootSshKeyManager;
import jetbrains.buildServer.util.FuncThrow;
import jetbrains.buildServer.util.NamedThreadFactory;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.CommitResult;
import jetbrains.buildServer.vcs.VcsException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jetbrains.annotations.NotNull;

public class NativeGitCommands implements FetchCommand, LsRemoteCommand, PushCommand, TagCommand {

  private static final Logger PERFORMANCE_LOG = Logger.getInstance(GitVcsSupport.class.getName() + ".Performance");
  private static final GitVersion GIT_WITH_PROGRESS_VERSION = new GitVersion(1, 7, 1, 0);

  private final ServerPluginConfig myConfig;
  private final GitDetector myGitDetector;
  private final VcsRootSshKeyManager mySshKeyManager;

  public NativeGitCommands(@NotNull ServerPluginConfig config,
                           @NotNull GitDetector gitDetector,
                           @NotNull VcsRootSshKeyManager sshKeyManager) {
    myConfig = config;
    myGitDetector = gitDetector;
    mySshKeyManager = sshKeyManager;
  }

  private static boolean isSilentFetch(@NotNull Context ctx) {
    return ctx.getGitVersion().isLessThan(GIT_WITH_PROGRESS_VERSION);
  }

  // Visible for testing
  protected <R> R executeCommand(@NotNull Context ctx, @NotNull String action, @NotNull String debugInfo, @NotNull FuncThrow<R, VcsException> cmd, RefSpec... refSpecs) throws VcsException{
    return NamedThreadFactory.executeWithNewThreadNameFuncThrow(
      String.format("Running native git %s process for : %s", action, debugInfo),
      () -> {
        final long start = System.currentTimeMillis();
        try {
          return cmd.apply();
        } finally {
          final long finish = System.currentTimeMillis();
          final String msg = "[git " + action + "] repository: " + debugInfo + " took " + (finish - start) + "ms";
          if (ctx.isDebugGitCommands()) PERFORMANCE_LOG.info(msg);
          else PERFORMANCE_LOG.debug(msg);
        }
      });
  }

  @Override
  public void fetch(@NotNull Repository db, @NotNull URIish fetchURI, @NotNull Collection<RefSpec> refspecs, @NotNull FetchSettings settings) throws IOException, VcsException {
    final Context ctx = new ContextImpl(myConfig, myGitDetector.detectGit(), settings.getProgress());
    final GitFacadeImpl gitFacade = new GitFacadeImpl(db.getDirectory(), ctx);
    gitFacade.setSshKeyManager(mySshKeyManager);

    // Before running fetch we need to prune branches which no longer exist in the remote,
    // otherwise git fails to update local branches which were e.g. renamed.
    try {
      gitFacade.remote()
               .setCommand("prune").setRemote("origin")
               .setAuthSettings(settings.getAuthSettings()).setUseNativeSsh(true)
               .trace(myConfig.getGitTraceEnv())
               .call();
    } catch (VcsException e) {
      Loggers.VCS.warnAndDebugDetails("Error while pruning removed branches in " + db, e);
    }

    final jetbrains.buildServer.buildTriggers.vcs.git.command.FetchCommand fetch =
      gitFacade.fetch()
               .setRemote(fetchURI.toString())
               .setFetchTags(false)
               .setAuthSettings(settings.getAuthSettings()).setUseNativeSsh(true)
               .setTimeout(myConfig.getFetchTimeout())
               .setRetryAttempts(myConfig.getConnectionRetryAttempts())
               .trace(myConfig.getGitTraceEnv())
               .addPreAction(() -> GitServerUtil.removeRefLocks(db.getDirectory()));

    for (RefSpec refSpec : refspecs) {
      fetch.setRefspec(refSpec.toString());
    }

    if (isSilentFetch(ctx))
      fetch.setQuite(true);
    else
      fetch.setShowProgress(true);

    executeCommand(ctx, "fetch", getDebugInfo(db, fetchURI, refspecs), () -> {
      fetch.call();
      return true;
    });
  }

  @NotNull
  @Override
  public Map<String, Ref> lsRemote(@NotNull Repository db, @NotNull GitVcsRoot gitRoot, @NotNull FetchSettings settings) throws VcsException {
    final Context ctx = new ContextImpl(myConfig, myGitDetector.detectGit(), settings.getProgress());
    final GitFacadeImpl gitFacade = new GitFacadeImpl(db.getDirectory(), ctx);
    gitFacade.setSshKeyManager(mySshKeyManager);

    final jetbrains.buildServer.buildTriggers.vcs.git.command.LsRemoteCommand lsRemote =
      gitFacade.lsRemote()
               .peelRefs()
               .setAuthSettings(gitRoot.getAuthSettings()).setUseNativeSsh(true)
               .setTimeout(myConfig.getRepositoryStateTimeoutSeconds())
               .setRetryAttempts(myConfig.getConnectionRetryAttempts())
               .trace(myConfig.getGitTraceEnv());

    return executeCommand(ctx, "ls-remote", LogUtil.describe(gitRoot), () -> {
      return lsRemote.call().stream().collect(Collectors.toMap(Ref::getName, ref -> ref));
    });
  }

  @NotNull
  @Override
  public CommitResult push(@NotNull Repository db, @NotNull GitVcsRoot gitRoot, @NotNull String ref, @NotNull String commit, @NotNull String lastCommit) throws VcsException {

    final String fullRef = GitUtils.expandRef(ref);

    final Context ctx = new ContextImpl(myConfig, myGitDetector.detectGit());
    final GitFacadeImpl gitFacade = new GitFacadeImpl(db.getDirectory(), ctx);
    gitFacade.setSshKeyManager(mySshKeyManager);

    gitFacade.updateRef().setRef(fullRef).setRevision(commit).setOldValue(lastCommit).call();

    final String debugInfo = LogUtil.describe(gitRoot);
    try {
      return executeCommand(ctx, "push", debugInfo, () -> {
        gitFacade.push()
                 .setRemote(gitRoot.getRepositoryPushURL().toString())
                 .setRefspec(fullRef)
                 .setAuthSettings(gitRoot.getAuthSettings()).setUseNativeSsh(true)
                 .setTimeout(myConfig.getPushTimeoutSeconds())
                 .trace(myConfig.getGitTraceEnv())
                 .call();
        return CommitResult.createSuccessResult(commit);
      });
    } catch (VcsException e) {
      // restore local ref
      try {
        gitFacade.updateRef().setRef(fullRef).setRevision(lastCommit).setOldValue(commit).call();
      } catch (VcsException v) {
        Loggers.VCS.warn("Failed to restore initial revision " + lastCommit + " of " + fullRef + " after unssuccessful push of revision " + commit + " for " + debugInfo, v);
      }
      throw e;
    }
  }

  @Override
  @NotNull
  public String tag(@NotNull OperationContext context, @NotNull String tag, @NotNull String commit) throws VcsException {
    final Context ctx = new ContextImpl(myConfig, myGitDetector.detectGit());
    final Repository db = context.getRepository();
    final GitFacadeImpl gitFacade = new GitFacadeImpl(db.getDirectory(), ctx);
    gitFacade.setSshKeyManager(mySshKeyManager);

    final GitVcsRoot gitRoot = context.getGitRoot();

    final PersonIdent tagger = PersonIdentFactory.getTagger(gitRoot, db);
    gitFacade.tag().setName(tag).setCommit(commit).force(true).annotate(true)
             .setTagger(tagger.getName(), tagger.getEmailAddress()).setMessage("automatically created by TeamCity VCS labeling build feature").call();

    final String debugInfo = LogUtil.describe(gitRoot);
    try {
      return executeCommand(ctx, "push", debugInfo, () -> {
        gitFacade.push()
                 .setRemote(gitRoot.getRepositoryPushURL().toString())
                 .setRefspec(tag)
                 .setAuthSettings(gitRoot.getAuthSettings()).setUseNativeSsh(true)
                 .setTimeout(myConfig.getPushTimeoutSeconds())
                 .trace(myConfig.getGitTraceEnv())
                 .call();
        Loggers.VCS.info("Tag '" + tag + "' was successfully pushed for " + debugInfo);
        return tag;
      });
    } catch (VcsException e) {
      // remove local tag
      try {
        gitFacade.tag().delete(true).setName(tag).call();
      } catch (VcsException v) {
        Loggers.VCS.warn("Failed to delete local tag " + tag + " of " + commit + " after unssuccessful push for " + debugInfo, v);
      }
      throw e;
    }
  }

  @NotNull
  private String getDebugInfo(@NotNull Repository db, @NotNull URIish uri, @NotNull Collection<RefSpec> refSpecs) {
    final StringBuilder sb = new StringBuilder();
    sb.append("(").append(db.getDirectory() != null? db.getDirectory().getAbsolutePath() + ", ":"").append(uri);
    for (RefSpec spec : refSpecs) {
      sb.append(", ").append(spec);
    }
    sb.append(")");

    final int commandLineStrLimit = TeamCityProperties.getInteger("teamcity.externalProcessRunner.limitCommandLineLengthInThreadName", 1000);
    return StringUtil.truncateStringValueWithDotsAtCenter(sb.toString(), commandLineStrLimit);
  }
}
