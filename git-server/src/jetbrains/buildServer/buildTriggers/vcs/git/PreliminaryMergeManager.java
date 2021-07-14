package jetbrains.buildServer.buildTriggers.vcs.git;

import java.util.AbstractMap;
import java.util.Map;
import java.util.regex.Pattern;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.vcs.RepositoryState;
import jetbrains.buildServer.vcs.RepositoryStateListener;
import jetbrains.buildServer.vcs.VcsRoot;
import org.jetbrains.annotations.NotNull;

public class PreliminaryMergeManager implements RepositoryStateListener {

  public PreliminaryMergeManager(@NotNull final EventDispatcher<RepositoryStateListener> repositoryStateEvents) {
    printTmp("GitPluginPM init");
    repositoryStateEvents.addListener(this);
  }

  private void printTmp(String toPrint) {
    Loggers.VCS.debug(toPrint);
    System.out.println(toPrint);
  }

  private String branchRevisionsToString(Map<String, String> revs) {
    StringBuilder revisions = new StringBuilder();
    revs.forEach((key, value) -> revisions.append("\n{\n\t").append(key).append(" : ").append(value).append("\n}"));
    return revisions.append("\n").toString();
  }

  @Override
  public void beforeRepositoryStateUpdate(@NotNull VcsRoot root, @NotNull RepositoryState oldState, @NotNull RepositoryState newState) {

  }

  @Override
  public void repositoryStateChanged(@NotNull VcsRoot root, @NotNull RepositoryState oldState, @NotNull RepositoryState newState) {
    printTmp("GitPluginPM: new state: " +
             branchRevisionsToString(oldState.getBranchRevisions()) +
             " > " +
             branchRevisionsToString(newState.getBranchRevisions()));

    VcsRootParametersExtractor paramsExecutor = new VcsRootParametersExtractor(root);

    //parameter: teamcity.internal.vcs.preliminaryMerge.<VCS root external id>=<source branch pattern>:<target branch name>
    Map.Entry<String, String> exIdOnSourcesTargetBranchesParam = paramsExecutor.getParameterWithExternalId("teamcity.internal.vcs.preliminaryMerge");
    if (exIdOnSourcesTargetBranchesParam == null) {
      return;
    }

    Map.Entry<String, String> sourcesTargetBranches = parsePreliminaryMergeSourcesTargetBranches(exIdOnSourcesTargetBranchesParam.getValue());

    System.out.println("source pattern: " + sourcesTargetBranches.getKey());
    System.out.println("target branch: " + sourcesTargetBranches.getValue());
  }

  private Map.Entry<String, String> parsePreliminaryMergeSourcesTargetBranches(String paramValue) {
    //regexes are too slow for this simple task
    for (int i = 1; i < paramValue.length(); ++i) {
      if (paramValue.charAt(i) == ':' && paramValue.charAt(i-1) != '+' && paramValue.charAt(i-1) != '0') {
        return new AbstractMap.SimpleEntry<>(paramValue.substring(0, i), paramValue.substring(i+1));
      }
    }

    return null; //todo exception???
  }
}
