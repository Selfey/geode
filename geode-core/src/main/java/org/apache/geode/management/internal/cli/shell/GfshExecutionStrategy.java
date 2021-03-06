/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.management.internal.cli.shell;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.springframework.shell.core.ExecutionStrategy;
import org.springframework.shell.core.Shell;
import org.springframework.shell.event.ParseResult;
import org.springframework.util.Assert;

import org.apache.geode.internal.ClassPathLoader;
import org.apache.geode.management.cli.CliMetaData;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.cli.Result.Status;
import org.apache.geode.management.internal.cli.CliAroundInterceptor;
import org.apache.geode.management.internal.cli.CommandRequest;
import org.apache.geode.management.internal.cli.CommandResponse;
import org.apache.geode.management.internal.cli.CommandResponseBuilder;
import org.apache.geode.management.internal.cli.GfshParseResult;
import org.apache.geode.management.internal.cli.LogWrapper;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.remote.CommandExecutor;
import org.apache.geode.management.internal.cli.result.FileResult;
import org.apache.geode.management.internal.cli.result.ModelCommandResult;
import org.apache.geode.management.internal.cli.result.ResultBuilder;
import org.apache.geode.management.internal.cli.result.model.ResultModel;
import org.apache.geode.security.NotAuthorizedException;

/**
 * Defines the {@link ExecutionStrategy} for commands that are executed in GemFire Shell (gfsh).
 *
 * @since GemFire 7.0
 */
public class GfshExecutionStrategy implements ExecutionStrategy {
  private Class<?> mutex = GfshExecutionStrategy.class;
  private Gfsh shell;
  private LogWrapper logWrapper;

  GfshExecutionStrategy(Gfsh shell) {
    this.shell = shell;
    this.logWrapper = shell.getGfshFileLogger();
  }

  /**
   * Executes the method indicated by the {@link ParseResult} which would always be
   * {@link GfshParseResult} for GemFire defined commands. If the command Method is decorated with
   * {@link CliMetaData#shellOnly()} set to <code>false</code>, {@link OperationInvoker} is used to
   * send the command for processing on a remote GemFire node.
   *
   * @param parseResult that should be executed (never presented as null)
   * @return an object which will be rendered by the {@link Shell} implementation (may return null)
   * @throws RuntimeException which is handled by the {@link Shell} implementation
   */
  @Override
  public Object execute(ParseResult parseResult) {
    Result result;
    Method method = parseResult.getMethod();

    // check if it's a shell only command
    if (isShellOnly(method)) {
      Assert.notNull(parseResult, "Parse result required");
      synchronized (mutex) {
        Assert.isTrue(isReadyForCommands(), "Not yet ready for commands");

        Object exeuctionResult = new CommandExecutor().execute((GfshParseResult) parseResult);
        if (exeuctionResult instanceof ResultModel) {
          return new ModelCommandResult((ResultModel) exeuctionResult);
        }
        return exeuctionResult;

      }
    }

    // check if it's a GfshParseResult
    if (!GfshParseResult.class.isInstance(parseResult)) {
      throw new IllegalStateException("Configuration error!");
    }

    result = executeOnRemote((GfshParseResult) parseResult);
    return result;
  }

  /**
   * Whether the command is available only at the shell or on GemFire member too.
   *
   * @param method the method to check the associated annotation
   * @return true if CliMetaData is added to the method & CliMetaData.shellOnly is set to true,
   *         false otherwise
   */
  private boolean isShellOnly(Method method) {
    CliMetaData cliMetadata = method.getAnnotation(CliMetaData.class);
    return cliMetadata != null && cliMetadata.shellOnly();
  }

  private String getInterceptor(Method method) {
    CliMetaData cliMetadata = method.getAnnotation(CliMetaData.class);
    return cliMetadata != null ? cliMetadata.interceptor() : CliMetaData.ANNOTATION_NULL_VALUE;
  }

  /**
   * Indicates commands are able to be presented. This generally means all important system startup
   * activities have completed. Copied from {@link ExecutionStrategy#isReadyForCommands()}.
   *
   * @return whether commands can be presented for processing at this time
   */
  @Override
  public boolean isReadyForCommands() {
    return true;
  }

  /**
   * Indicates the execution runtime should be terminated. This allows it to cleanup before
   * returning control flow to the caller. Necessary for clean shutdowns. Copied from
   * {@link ExecutionStrategy#terminate()}.
   */
  @Override
  public void terminate() {
    shell = null;
  }

  /**
   * Sends the user input (command string) via {@link OperationInvoker} to a remote GemFire node for
   * processing & execution.
   *
   * @return result of execution/processing of the command
   * @throws IllegalStateException if gfsh doesn't have an active connection.
   */
  private Result executeOnRemote(GfshParseResult parseResult) {
    Result commandResult = null;
    Object response = null;
    Path tempFile = null;

    if (!shell.isConnectedAndReady()) {
      shell.logWarning(
          "Can't execute a remote command without connection. Use 'connect' first to connect.",
          null);
      logWrapper.info("Can't execute a remote command \"" + parseResult.getUserInput()
          + "\" without connection. Use 'connect' first to connect to GemFire.");
      return null;
    }

    List<File> fileData = null;
    CliAroundInterceptor interceptor = null;

    String interceptorClass = getInterceptor(parseResult.getMethod());

    // 1. Pre Remote Execution
    if (!CliMetaData.ANNOTATION_NULL_VALUE.equals(interceptorClass)) {
      try {
        interceptor = (CliAroundInterceptor) ClassPathLoader.getLatest().forName(interceptorClass)
            .newInstance();
      } catch (InstantiationException | ClassNotFoundException | IllegalAccessException e) {
        shell.logWarning("Configuration error", e);
      }

      if (interceptor == null) {
        return ResultBuilder.createBadConfigurationErrorResult("Interceptor Configuration Error");
      }

      Object preExecResult = interceptor.preExecution(parseResult);
      if (preExecResult instanceof ResultModel) {
        if (((ResultModel) preExecResult).getStatus() != Status.OK) {
          return new ModelCommandResult((ResultModel) preExecResult);
        }
      }

      if (preExecResult instanceof Result) {
        if (Status.ERROR.equals(((Result) preExecResult).getStatus())) {
          return (Result) preExecResult;
        }
      }

      // when the preExecution yields a FileResult, we will get the fileData out of it
      if (preExecResult instanceof FileResult) {
        FileResult fileResult = (FileResult) preExecResult;
        fileData = fileResult.getFiles();
      }
    }

    // 2. Remote Execution
    final Map<String, String> env = shell.getEnv();
    try {
      response = shell.getOperationInvoker()
          .processCommand(new CommandRequest(parseResult, env, fileData));

      if (response == null) {
        return ResultBuilder
            .createBadResponseErrorResult("Response was null for: " + parseResult.getUserInput());
      }
    } catch (NotAuthorizedException e) {
      return ResultBuilder
          .createGemFireUnAuthorizedErrorResult("Unauthorized. Reason : " + e.getMessage());
    } catch (Exception e) {
      shell.logSevere(e.getMessage(), e);
      e.printStackTrace();
      return ResultBuilder.createBadResponseErrorResult(
          "Error occurred while executing \"" + parseResult.getUserInput() + "\" on manager.");
    } finally {
      env.clear();
    }

    // the response could be a string which is a json representation of the CommandResult object
    // it can also be a Path to a temp file downloaded from the rest http request
    if (response instanceof String) {
      try {
        // TODO: stuff when failedToPersist...
        // TODO: stuff for debug info...
        commandResult = ResultBuilder.createModelBasedCommandResult((String) response);
      } catch (Exception ex) {
        CommandResponse commandResponse =
            CommandResponseBuilder.prepareCommandResponseFromJson((String) response);

        if (commandResponse.isFailedToPersist()) {
          shell.printAsSevere(CliStrings.SHARED_CONFIGURATION_FAILED_TO_PERSIST_COMMAND_CHANGES);
          logWrapper.severe(CliStrings.SHARED_CONFIGURATION_FAILED_TO_PERSIST_COMMAND_CHANGES);
        }

        String debugInfo = commandResponse.getDebugInfo();
        if (StringUtils.isNotBlank(debugInfo)) {
          debugInfo = debugInfo.replaceAll("\n\n\n", "\n");
          debugInfo = debugInfo.replaceAll("\n\n", "\n");
          debugInfo =
              debugInfo.replaceAll("\n", "\n[From Manager : " + commandResponse.getSender() + "]");
          debugInfo = "[From Manager : " + commandResponse.getSender() + "]" + debugInfo;
          this.logWrapper.info(debugInfo);
        }
        commandResult = ResultBuilder.fromJson((String) response);
      }
    } else if (response instanceof Path) {
      tempFile = (Path) response;
    }

    // 3. Post Remote Execution
    if (interceptor != null) {
      Result postExecResult = interceptor.postExecution(parseResult, commandResult, tempFile);
      if (postExecResult != null) {
        if (Status.ERROR.equals(postExecResult.getStatus())) {
          if (logWrapper.infoEnabled()) {
            logWrapper.info("Post execution Result :: " + postExecResult);
          }
        } else if (logWrapper.fineEnabled()) {
          logWrapper.fine("Post execution Result :: " + postExecResult);
        }
        commandResult = postExecResult;
      }
    }

    if (commandResult == null) {
      commandResult = ResultBuilder
          .createGemFireErrorResult("Unable to build commandResult using the remote response.");
    }

    return commandResult;
  }
}
