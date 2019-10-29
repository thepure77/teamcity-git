/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.git;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.ExecResult;
import jetbrains.buildServer.StreamGobbler;
import jetbrains.buildServer.buildTriggers.vcs.git.process.GitProcessExecutor;
import jetbrains.buildServer.buildTriggers.vcs.git.process.GitProcessStuckMonitor;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.ssh.TeamCitySshKey;
import jetbrains.buildServer.ssh.VcsRootSshKeyManager;
import jetbrains.buildServer.util.Dates;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.VcsUtil;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.LockFile;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
* @author dmitry.neverov
*/
public class FetchCommandImpl implements FetchCommand {

  private static Logger LOG = Logger.getInstance(FetchCommandImpl.class.getName());

  private final ServerPluginConfig myConfig;
  private final TransportFactory myTransportFactory;
  private final FetcherProperties myFetcherProperties;
  private final VcsRootSshKeyManager mySshKeyManager;
  private final GitTrustStoreProvider myGitTrustStoreProvider;
  private final MemoryStorage myMemoryStorage;

  public FetchCommandImpl(@NotNull ServerPluginConfig config,
                          @NotNull TransportFactory transportFactory,
                          @NotNull FetcherProperties fetcherProperties,
                          @NotNull VcsRootSshKeyManager sshKeyManager,
                          final MemoryStorage memoryStorage) {
    this(config, transportFactory, fetcherProperties, sshKeyManager, new GitTrustStoreProviderStatic(null), memoryStorage);
  }

  public FetchCommandImpl(@NotNull ServerPluginConfig config,
                          @NotNull TransportFactory transportFactory,
                          @NotNull FetcherProperties fetcherProperties,
                          @NotNull VcsRootSshKeyManager sshKeyManager,
                          @NotNull GitTrustStoreProvider gitTrustStoreProvider,
                          final MemoryStorage memoryStorage) {
    myConfig = config;
    myTransportFactory = transportFactory;
    myFetcherProperties = fetcherProperties;
    mySshKeyManager = sshKeyManager;
    myGitTrustStoreProvider = gitTrustStoreProvider;
    myMemoryStorage = memoryStorage;
  }

  public void fetch(@NotNull Repository db,
                    @NotNull URIish fetchURI,
                    @NotNull Collection<RefSpec> refspecs,
                    @NotNull FetchSettings settings) throws IOException, VcsException {
    unlockRefs(db);
    if (myConfig.isSeparateProcessForFetch()) {
      fetchInSeparateProcess(db, fetchURI, refspecs, settings);
    } else {
      fetchInSameProcess(db, fetchURI, refspecs, settings);
    }
  }


  private void unlockRefs(@NotNull Repository db) throws VcsException{
    try {
      for (Ref ref : findLockedRefs(db)) {
        unlockRef(db, ref);
      }
    } catch (Exception e) {
      throw new VcsException(e);
    }
  }


  @NotNull
  private List<Ref> findLockedRefs(@NotNull Repository db) {
    File refsDir = new File(db.getDirectory(), org.eclipse.jgit.lib.Constants.R_REFS);
    List<String> lockFiles = new ArrayList<>();
    //listFilesRecursively always uses / as a separator, we get valid ref names on all OSes
    FileUtil.listFilesRecursively(refsDir, "refs/", false, Integer.MAX_VALUE, f -> f.isDirectory() || f.isFile() && f.getName().endsWith(".lock"), lockFiles);
    Map<String, Ref> allRefs = db.getAllRefs();
    List<Ref> result = new ArrayList<>();
    for (String lock : lockFiles) {
      String refName = lock.substring(0, lock.length() - ".lock".length());
      Ref ref = allRefs.get(refName);
      if (ref != null)
        result.add(ref);
    }
    return result;
  }


  private void unlockRef(Repository db, Ref ref) throws IOException, InterruptedException {
    File refFile = new File(db.getDirectory(), ref.getName());
    File refLockFile = new File(db.getDirectory(), ref.getName() + ".lock");
    LockFile lock = new LockFile(refFile);
    try {
      if (!lock.lock()) {
        LOG.warn("Cannot lock the ref " + ref.getName() + ", will wait and try again");
        Thread.sleep(5000);
        if (lock.lock()) {
          LOG.warn("Successfully lock the ref " + ref.getName());
        } else {
          if (FileUtil.delete(refLockFile)) {
            LOG.warn("Remove ref lock " + refLockFile.getAbsolutePath());
          } else {
            LOG.warn("Cannot remove ref lock " + refLockFile.getAbsolutePath() + ", fetch will probably fail. Please remove lock manually.");
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }


  private void fetchInSeparateProcess(@NotNull Repository repository,
                                      @NotNull URIish uri,
                                      @NotNull Collection<RefSpec> specs,
                                      @NotNull FetchSettings settings) throws VcsException {
    new FetchMemoryProvider(uri.toString(), myMemoryStorage, myConfig).withXmx((xmx, canIncrease)  -> {
      if (attemptFetchInSeparateProcess(xmx, repository, uri, specs, settings)) return true;

      LOG.warn("There is not enough memory for git fetch (" + xmx + "M)" + (canIncrease ? ", will retry with increased value" : ""));
      return false;
    }
    );
  }

  private boolean attemptFetchInSeparateProcess(long xmx,
                                                @NotNull Repository repository,
                                                @NotNull URIish uri,
                                                @NotNull Collection<RefSpec> specs,
                                                @NotNull FetchSettings settings) throws VcsException {
    File gitPropertiesFile = null;
    File teamcityPrivateKey = null;
    GitProcessStuckMonitor processStuckMonitor = null;
    try {

      File gcDump = getDumpFile(repository, "gc");
      gitPropertiesFile = myFetcherProperties.getPropertiesFile();
      teamcityPrivateKey = getTeamCityPrivateKey(settings.getAuthSettings());

      final GeneralCommandLine cl = createFetcherCommandLine(repository, uri, xmx);
      final String commandLineString = cl.getCommandLineString();
      final GitProcessExecutor processExecutor = new GitProcessExecutor(cl, procStream -> new StreamGobbler(procStream, null,
                                                                                                            "StdOut for " +
                                                                                                            commandLineString,
                                                                                                            settings.createStdoutBuffer()));
      processStuckMonitor = new GitProcessStuckMonitor(gcDump, xmx, commandLineString) {
        @Override
        protected void stuckDetected() {
          processExecutor.interrupt();
        }
      };
      processStuckMonitor.start();

      final String debugInfo = getDebugInfo(repository, uri, specs);

      final GitProcessExecutor.GitExecResult gitResult = processExecutor.runProcess(
        getFetchProcessInputBytes(getAuthSettings(settings, teamcityPrivateKey), repository.getDirectory(), uri, specs, getDumpFile(repository, null), gcDump, gitPropertiesFile),
        myConfig.getFetchTimeout(),
        new GitProcessExecutor.ProcessExecutorAdapter() {
          @Override
          public void processStarted() {
            if (LOG.isDebugEnabled()) LOG.debug("Fetch process for " + debugInfo + " started in separate process with command line: " + commandLineString);
            settings.getProgress().reportProgress("git fetch " + uri);
          }

          @Override
          public void processFinished() {
            if (LOG.isDebugEnabled()) LOG.debug("Fetch process for " + debugInfo + " finished");
            settings.getProgress().reportProgress("git fetch " + uri + " finished");
          }

          @Override
          public void processFailed(@NotNull final ExecutionException e) {
            if (LOG.isDebugEnabled()) LOG.debug("Fetch process for " + debugInfo + " failed");
            settings.getProgress().reportProgress("git fetch " + uri + " failed");
          }
        });


      final ExecResult result = gitResult.getExecResult();
      final VcsException commandError = CommandLineUtil.getCommandLineError("git fetch",
                                                                            " (repository dir: <TeamCity data dir>/system/caches/git/" +
                                                                            repository.getDirectory().getName() + ")",
                                                                            result, true, true);
      if (commandError != null) {

        LOG.info("Git fetch process failed for: " + uri + " in directory: " + repository.getDirectory() + ", took " + gitResult.getDuration() + "ms");
        commandError.setRecoverable(isRecoverable(commandError));

        /* if the process had no enough memory or we kill it because gc */
        if (gitResult.isOutOfMemoryError() || gitResult.isInterrupted()) {
          clean(repository);
          return false;
        }

        if (gitResult.isTimeout()) {
          logTimeout(debugInfo, getDumpFile(repository, null));
        }

        clean(repository);
        throw commandError;
      }

      LOG.info("Git fetch process finished for: " + uri + " in directory: " + repository.getDirectory() + ", took " + gitResult.getDuration() + "ms");

      if (result.getStderr().length() > 0) {
        LOG.warn("Error output produced by git fetch:\n" + result.getStderr());
      }

      LOG.debug("Fetch process output:\n" + result.getStdout());
    } finally {
      if (teamcityPrivateKey != null) {
        FileUtil.delete(teamcityPrivateKey);
      }
      if (gitPropertiesFile != null) {
        FileUtil.delete(gitPropertiesFile);
      }
      if (processStuckMonitor != null) {
        processStuckMonitor.finish();
      }
    }

    return true;
  }

  @NotNull
  private AuthSettings getAuthSettings(@NotNull final FetchSettings settings, @Nullable final File teamcityPrivateKey) {
    AuthSettings preparedSettings = settings.getAuthSettings();
    if (teamcityPrivateKey != null) {
      Map<String, String> properties = settings.getAuthSettings().toMap();
      properties.put(Constants.AUTH_METHOD, AuthenticationMethod.PRIVATE_KEY_FILE.name());
      properties.put(Constants.PRIVATE_KEY_PATH, teamcityPrivateKey.getAbsolutePath());
      preparedSettings = new AuthSettings(properties, settings.getAuthSettings().getRoot(), new URIishHelperImpl());
    }
    return preparedSettings;
  }

  private boolean isRecoverable(@NotNull VcsException exception) {
    String message = exception.getMessage();
    for (String recoverableErrorMsg : myConfig.getRecoverableFetchErrorMessages()) {
      if (message.contains(recoverableErrorMsg))
        return true;
    }
    return false;
  }

  private File getTeamCityPrivateKey(@NotNull AuthSettings authSettings) throws VcsException {
    if (authSettings.getAuthMethod() != AuthenticationMethod.TEAMCITY_SSH_KEY)
      return null;

    String keyId = authSettings.getTeamCitySshKeyId();
    if (keyId == null)
      return null;

    VcsRoot root = authSettings.getRoot();
    if (root == null)
      return null;

    TeamCitySshKey privateKey = mySshKeyManager.getKey(root);
    if (privateKey == null)
      return null;

    try {
      File privateKeyFile = FileUtil.createTempFile("private", "key");
      FileUtil.writeToFile(privateKeyFile, privateKey.getPrivateKey());
      return privateKeyFile;
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  private void logTimeout(@NotNull String debugInfo, @NotNull File threadDump) {
    StringBuilder message = new StringBuilder();
    message.append("Fetch in root ").append(debugInfo)
      .append(" took more than ")
      .append(myConfig.getFetchTimeout())
      .append(" second(s), try increasing a timeout using the " + PluginConfigImpl.TEAMCITY_GIT_IDLE_TIMEOUT_SECONDS + " property.");
    if (threadDump.exists())
      message.append(" Fetch progress details can be found in ").append(threadDump.getAbsolutePath());
    LOG.warn(message.toString());
  }

  private File getDumpFile(@NotNull Repository repository, @Nullable String nameSuffix) {
    File dumpsDir = getMonitoringDir(repository);
    //noinspection ResultOfMethodCallIgnored
    dumpsDir.mkdirs();
    final String suffix = nameSuffix != null ? nameSuffix : "";
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
    return new File(dumpsDir, sdf.format(Dates.now()) + suffix + ".txt");
  }

  private File getMonitoringDir(@NotNull Repository repository) {
    return new File(repository.getDirectory(), myConfig.getMonitoringDirName());
  }

  private GeneralCommandLine createFetcherCommandLine(
    @NotNull final Repository repository, @NotNull final URIish uri, long xmx
  ) {
    GeneralCommandLine cl = new GeneralCommandLine();
    cl.setWorkingDirectory(repository.getDirectory());
    cl.setExePath(myConfig.getFetchProcessJavaPath());
    cl.addParameters(myConfig.getOptionsForSeparateProcess());
    cl.setPassParentEnvs(myConfig.passEnvToChildProcess());

    cl.addParameters("-Xmx" + xmx + "M",
                     "-cp", myConfig.getFetchClasspath(),
                     myConfig.getFetcherClassName(),
                     uri.toString());//last parameter is not used in Fetcher, but is useful to distinguish fetch processes
    return cl;
  }


  private void fetchInSameProcess(@NotNull Repository db,
                                  @NotNull URIish uri,
                                  @NotNull Collection<RefSpec> refSpecs,
                                  @NotNull FetchSettings settings) throws IOException, VcsException {
    final String debugInfo = getDebugInfo(db, uri, refSpecs);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Fetch in server process: " + debugInfo);
    }
    final long fetchStart = System.currentTimeMillis();
    final Transport tn = myTransportFactory.createTransport(db, uri, settings.getAuthSettings());
    try {
      pruneRemovedBranches(db, tn, uri, settings.getAuthSettings());
      FetchResult result = GitServerUtil.fetch(db, uri, settings.getAuthSettings(), myTransportFactory, tn, settings.createProgressMonitor(), refSpecs, myConfig.ignoreMissingRemoteRef());
      GitServerUtil.checkFetchSuccessful(db, result);
    } catch (OutOfMemoryError oom) {
      LOG.warn("There is not enough memory for git fetch, try to run fetch in a separate process.");
      clean(db);
    } finally {
      clean(db);
      tn.close();
      final long fetchTime = System.currentTimeMillis() - fetchStart;
      LOG.info("Git fetch finished for: " + uri + " in directory: " + db.getDirectory() + ", took " + fetchTime + "ms");
    }
  }

  private void pruneRemovedBranches(@NotNull Repository db, @NotNull Transport tn, @NotNull URIish uri, @NotNull AuthSettings authSettings) throws IOException, VcsException {
    try {
      GitServerUtil.pruneRemovedBranches(myConfig, myTransportFactory, tn, db, uri, authSettings);
    } catch (Exception e) {
      LOG.error("Error while pruning removed branches", e);
    }
  }

  private String getDebugInfo(Repository db, URIish uri, Collection<RefSpec> refSpecs) {
    StringBuilder sb = new StringBuilder();
    for (RefSpec spec : refSpecs) {
      sb.append(spec).append(" ");
    }
    return " (" + (db.getDirectory() != null? db.getDirectory().getAbsolutePath() + ", ":"") + uri.toString() + "#" + sb.toString() + ")";
  }


  /**
   * Clean out garbage in case of errors
   * @param db repository
   */
  private void clean(Repository db) {
    //When jgit loads new pack into repository, it first writes it to file
    //incoming_xxx.pack. When it tries to open such pack we can run out of memory.
    //In this case incoming_xxx.pack files will waste disk space.
    //See TW-13450 for details
    File objectsDir = ((FileRepository) db).getObjectsDirectory();
    File[] files = objectsDir.listFiles();
    if (files == null)
      return;
    for (File f : files) {
      if (f.isFile() && f.getName().startsWith("incoming_") && f.getName().endsWith(".pack")) {
        FileUtil.delete(f);
      }
    }
  }

  private byte[] getFetchProcessInputBytes(@NotNull AuthSettings authSettings,
                                           @NotNull File repositoryDir,
                                           @NotNull URIish uri,
                                           @NotNull Collection<RefSpec> specs,
                                           @NotNull File threadDump,
                                           @NotNull File gcDump,
                                           @NotNull File gitProperties) throws VcsException {
    try {
      Map<String, String> properties = new HashMap<String, String>(authSettings.toMap());
      properties.put(Constants.REPOSITORY_DIR_PROPERTY_NAME, repositoryDir.getCanonicalPath());
      properties.put(Constants.FETCH_URL, uri.toString());
      properties.put(Constants.REFSPEC, serializeSpecs(specs));
      properties.put(Constants.VCS_DEBUG_ENABLED, String.valueOf(Loggers.VCS.isDebugEnabled()));
      properties.put(Constants.THREAD_DUMP_FILE, threadDump.getAbsolutePath());
      properties.put(Constants.GC_DUMP_FILE, gcDump.getAbsolutePath());
      properties.put(Constants.FETCHER_INTERNAL_PROPERTIES_FILE, gitProperties.getAbsolutePath());
      final File trustedCertificatesDir = myGitTrustStoreProvider.getTrustedCertificatesDir();
      if (trustedCertificatesDir != null) {
        properties.put(Constants.GIT_TRUST_STORE_PROVIDER, trustedCertificatesDir.getAbsolutePath());
      }
      return VcsUtil.propertiesToStringSecure(properties).getBytes(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new VcsException("Error while generating fetch process input", e);
    }
  }

  private String serializeSpecs(@NotNull final Collection<RefSpec> specs) {
    StringBuilder sb = new StringBuilder();
    Iterator<RefSpec> iter = specs.iterator();
    while (iter.hasNext()) {
      RefSpec spec = iter.next();
      sb.append(spec);
      if (iter.hasNext())
        sb.append(Constants.RECORD_SEPARATOR);
    }
    return sb.toString();
  }
}
