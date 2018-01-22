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

package jetbrains.buildServer.buildTriggers.vcs.git.tests;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

public class GitCommandProxy implements InvocationHandler {
  private final Object myGitCommand;
  private final Class myGitCommandClass;
  private final List<String> myInvokedMethods;
  private final Map<String, GitCommandProxyCallback> myCallbacks;
  public GitCommandProxy(@NotNull Object gitCommand,
                         @NotNull Class gitCommandClass,
                         @NotNull List<String> invokedMethods,
                         @NotNull Map<String, GitCommandProxyCallback> callbacks) {
    myGitCommand = gitCommand;
    myGitCommandClass = gitCommandClass;
    myInvokedMethods = invokedMethods;
    myCallbacks = callbacks;
  }

  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    myInvokedMethods.add(method.getName());
    GitCommandProxyCallback callback = myCallbacks.get(myGitCommandClass.getName() + "." + method.getName());
    if (callback != null) {
      callback.call(method, args);
    }
    Object result = method.invoke(myGitCommand, args);
    if (myGitCommandClass.isInstance(result)) {//case of chaining
      return Proxy.newProxyInstance(GitFacadeProxy.class.getClassLoader(),
                                    new Class[]{myGitCommandClass},
                                    new GitCommandProxy(myGitCommand, myGitCommandClass, myInvokedMethods, myCallbacks));
    }
    return result;
  }
}
