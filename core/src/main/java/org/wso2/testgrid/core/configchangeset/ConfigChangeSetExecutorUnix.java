/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.testgrid.core.configchangeset;

import com.google.gson.Gson;
import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.testgrid.common.Agent;
import org.wso2.testgrid.common.ConfigChangeSet;
import org.wso2.testgrid.common.DeploymentCreationResult;
import org.wso2.testgrid.common.Host;
import org.wso2.testgrid.common.TestPlan;
import org.wso2.testgrid.common.config.ConfigurationContext;
import org.wso2.testgrid.common.util.StringUtil;
import org.wso2.testgrid.common.util.tinkerer.AsyncCommandResponse;
import org.wso2.testgrid.common.util.tinkerer.TinkererSDK;
import org.wso2.testgrid.common.util.tinkerer.exception.TinkererOperationException;
import org.wso2.testgrid.core.exception.ConfigChangeSetExecutorException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * This class execute commands for applying config change set on UNIX machines.
 *
 */
public class ConfigChangeSetExecutorUnix extends ConfigChangeSetExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ConfigChangeSetExecutorUnix.class);
    private static final String PRODUCT_HOME_ENV = "PRODUCT_HOME";

    /**
     * Apply config change set script before run test scenarios
     *
     * @param testPlan the test plan
     * @param configChangeSet   config change set
     * @param deploymentCreationResult
     * @return execution passed or failed
     */
    @Override
    public boolean applyConfigChangeSet(TestPlan testPlan, ConfigChangeSet configChangeSet,
                                        DeploymentCreationResult deploymentCreationResult) {
        if (testPlan.getConfigChangeSetRepository() != null) {
            Path configChangeSetRepoPath = Paths.get(testPlan.getConfigChangeSetRepository());
            Path fileName = configChangeSetRepoPath.getFileName();
            // set default branch as master if not mention in testgrid.yaml
            String branchName = "master";
            if (testPlan.getConfigChangeSetBranchName() != null) {
                branchName = testPlan.getConfigChangeSetBranchName();
            } else {
                logger.warn("Config change set repository branch name is not set. " +
                        "Using default repository branch as master for test plan id " + testPlan.getId() +
                " config change set " + configChangeSet.getName());
            }
            String configChangeSetLocation = "./repos/" + fileName + "-" + branchName + "/config-sets/" +
                    configChangeSet.getName();
            List<String> applyShellCommand = new ArrayList<String>();
            // Generate array of commands that need to be applied

            String productHomePath = System.getenv(PRODUCT_HOME_ENV);
            if (productHomePath == null) {
                logger.warn(PRODUCT_HOME_ENV +
                        "  environment variable is not set. JMeter test executions may fail.");
            } else {
                configChangeSetLocation = "export productHome=" +
                        productHomePath + " && " + configChangeSetLocation;
            }
            for (Host host : deploymentCreationResult.getHosts()) {
                configChangeSetLocation = "export " + host.getLabel() + "=" + host.getIp() + " && " +
                        configChangeSetLocation;
            }
            // run if config-init.sh exist
            applyShellCommand.add(configChangeSetLocation.concat("/config-init.sh &>/dev/null"));
            applyShellCommand.add(configChangeSetLocation.concat("/apply-config.sh &>/dev/null"));


            return applyShellCommandOnAgent(testPlan, applyShellCommand);
        } else {
            logger.error("Error parsing scenario repository path for ".
                    concat(testPlan.getConfigChangeSetRepository()));
            return false;
        }
    }

    /**
     * Revert config change set script after run test scenarios
     *
     * @param testPlan the test plan
     * @param configChangeSet the config change set
     * @param deploymentCreationResult deployment creation result
     * @return execution passed or failed
     */
    @Override
    public boolean revertConfigChangeSet(TestPlan testPlan, ConfigChangeSet configChangeSet,
                                         DeploymentCreationResult deploymentCreationResult) {
        if (testPlan.getConfigChangeSetRepository() != null) {
            Path configChangeSetRepoPath = Paths.get(testPlan.getConfigChangeSetRepository());
            Path fileName = configChangeSetRepoPath.getFileName();
            // set default branch as master if not mention in testgrid.yaml
            String branchName = "master";
            if (testPlan.getConfigChangeSetBranchName() != null) {
                branchName = testPlan.getConfigChangeSetBranchName();
            } else {
                logger.warn("Config change set repository branch name is not set. " +
                        "Using default repository branch as master");
            }
            String configChangeSetLocation = "./repos/" + fileName + "-" + branchName
                    + "/config-sets/" + configChangeSet.getName();
            List<String> applyShellCommand = new ArrayList<String>();
            // Generate array of commands that need to be applied

            String productHomePath = System.getenv(PRODUCT_HOME_ENV);
            if (productHomePath == null) {
                logger.warn(PRODUCT_HOME_ENV +
                        "  environment variable is not set. JMeter test executions may fail.");
            } else {
                configChangeSetLocation = "export productHome=" +
                        productHomePath + " && " + configChangeSetLocation;
            }
            for (Host host : deploymentCreationResult.getHosts()) {
                configChangeSetLocation = "export " + host.getLabel() + "=" + host.getIp() + " && " +
                        configChangeSetLocation;
            }
            // Generate array of commands that need to be applied
            applyShellCommand.add(configChangeSetLocation.concat("/revert-config.sh &>/dev/null"));
            return applyShellCommandOnAgent(testPlan, applyShellCommand);
        } else {
            logger.error("Error parsing scenario repository path for ".
                    concat(testPlan.getConfigChangeSetRepository()));
            return false;
        }
    }

    /**
     * Initialize agent before running config change set
     *
     * @param testPlan  The test plan
     * @return          True if execution success. Else, false
     */
    @Override
    public boolean initConfigChangeSet(TestPlan testPlan) {
        try {
            URL configChangeSetRepoPath = new URL(testPlan.getConfigChangeSetRepository());
            String filePath = configChangeSetRepoPath.getPath();
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
            List<String> initShellCommand = new ArrayList<>();
            // set default branch as master if not mention in testgrid.yaml
            String branchName = "master";
            if (testPlan.getConfigChangeSetBranchName() != null) {
                branchName = testPlan.getConfigChangeSetBranchName();
            } else {
                logger.warn("Config change set repository branch name is not set. " +
                        "Using default repository branch as master");
            }
            initShellCommand.add("mkdir repos");
            initShellCommand.add("cd repos && curl -LJO " +
                    testPlan.getConfigChangeSetRepository() + "/archive/" + branchName + ".tar.gz   &>/dev/null");
            initShellCommand.add("cd repos && tar xvzf " + fileName + "-" + branchName + ".tar.gz  &>/dev/null");
            initShellCommand.add("chmod -R 755 repos/" + fileName + "-" + branchName + "/config-sets/ &>/dev/null");
            return applyShellCommandOnAgent(testPlan, initShellCommand);
        } catch (MalformedURLException e) {
            logger.warn("Error parsing scenario repository path for ".
                    concat(testPlan.getConfigChangeSetRepository()), e);
            return false;
        }
    }

    /**
     * Revert back changes did in initConfigChangeSet
     *
     * @param testPlan  The test plan
     * @return          True if execution success. Else, false
     */
    @Override
    public boolean deInitConfigChangeSet(TestPlan testPlan) {
        List<String> revertShellCommand = new ArrayList<>();
        revertShellCommand.add("rm -rf repos");
        return applyShellCommandOnAgent(testPlan, revertShellCommand);
    }

    /**
     * Send an array of shell command to an agent
     *
     * @param testPlan          The test plan
     * @param initShellCommand  List of shell command
     * @return  True if execution success. Else, false
     */
    private boolean applyShellCommandOnAgent(TestPlan testPlan, List<String> initShellCommand) {
        String tinkererHost = ConfigurationContext.getProperty(
                ConfigurationContext.ConfigurationProperties.DEPLOYMENT_TINKERER_REST_BASE_PATH);
        // Get authentication detail from config.properties and encode with BASE64
        String authenticationString = ConfigurationContext.getProperty(
                ConfigurationContext.ConfigurationProperties.DEPLOYMENT_TINKERER_USERNAME) + ":" +
                ConfigurationContext.getProperty(
                        ConfigurationContext.ConfigurationProperties.DEPLOYMENT_TINKERER_PASSWORD);
        String authenticationToken = "Basic " + Base64.getEncoder().encodeToString(
                authenticationString.getBytes(StandardCharsets.UTF_8));
        boolean executionStatus = true;
        String agentLink = tinkererHost + "test-plan/" + testPlan.getId() + "/agents";
        try {
            // Get list of agent for given test plan id
            Content agentResponse = Request.Get(agentLink)
                    .addHeader(HttpHeaders.AUTHORIZATION, authenticationToken).
                            execute().returnContent();

            Agent[] agents = new Gson().fromJson(agentResponse.asString(), Agent[].class);
            // Execute command on found agents
            for (Agent agent : Arrays.asList(agents)) {
                logger.info("Start sending commands to agent " + agent.getAgentId());
                for (String shellCommand : initShellCommand) {
                    logger.info("Execute: " + shellCommand);
                    int exitValue = sendShellCommand(agent, shellCommand);
                    logger.info("Agent exit value: " + exitValue + "\n for Agent with id " + agent.getAgentId() +
                            " test plan id " + testPlan.getId());
                    // Agent code default is SHELL, if time out then, it return code as 408
                    if (exitValue != 0) {
                        executionStatus = false;
                        logger.error("Agent script execution failed with  exit value " + exitValue +
                                " for url " + tinkererHost);
                    }
                }
            }
            return executionStatus;
        } catch (ConfigChangeSetExecutorException e) {
            logger.error("Error in API call request to execute script ".concat(tinkererHost), e);
            return false;
        } catch (IOException e) {
            logger.error("Error in API call request to get Agent list ".concat(agentLink), e);
            return false;
        }
    }

    /**
     * Send a shell command to the agent and execute it
     *
     * @param agent                 Agent details
     * @param script                Script that need to be executed
     * @return                      Shell execution output
     * @throws ConfigChangeSetExecutorException          Request post exception
     */
    private int sendShellCommand(Agent agent, String script)
            throws ConfigChangeSetExecutorException {
        TinkererSDK tinkererSDK = new TinkererSDK();
        AsyncCommandResponse response = tinkererSDK.
                executeCommandAsync(agent.getAgentId(), script);
        try {
            String line;
            while (response.hasMoreContent()) {
                line = response.readLines();
                logger.info(line);
            }
            response.endReadStream();
        } catch (TinkererOperationException e) {
            throw new ConfigChangeSetExecutorException(StringUtil.concatStrings(
                    "Error while start reading persisted file for the operation ",
                    response.getOperationId()), e);
        }
        return response.getExitValue();
    }

}
