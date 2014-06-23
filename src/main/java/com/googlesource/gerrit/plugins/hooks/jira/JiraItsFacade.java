// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.hooks.jira;

import java.io.IOException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;

import com.atlassian.jira.rpc.soap.client.*;
import com.google.gerrit.server.data.AccountAttribute;
import org.apache.axis.AxisFault;
import org.apache.commons.collections.CollectionUtils;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;

import com.googlesource.gerrit.plugins.hooks.its.ItsFacade;

public class JiraItsFacade implements ItsFacade {

  private static final String GERRIT_CONFIG_USERNAME = "username";
  private static final String GERRIT_CONFIG_PASSWORD = "password";
  private static final String GERRIT_CONFIG_URL = "url";

  private static final int MAX_ATTEMPTS = 3;

  private Logger log = LoggerFactory.getLogger(JiraItsFacade.class);

  private final String pluginName;
  private Config gerritConfig;

  private JiraClient client;
  private JiraSession token;

	public JiraItsFacade() {
		this.pluginName = "ITS";
	}

  @Inject
  public JiraItsFacade(@PluginName String pluginName,
      @GerritServerConfig Config cfg) {
    this.pluginName = pluginName;
    try {
      this.gerritConfig = cfg;
      RemoteServerInfo info = client().getServerInfo(token);
      log.info("Connected to JIRA at " + info.getBaseUrl()
          + ", reported version is " + info.getVersion());
    } catch (Exception ex) {
      log.warn("Jira is currently not available", ex);
    }
  }

  @Override
  public String name() {
    return "Jira";
  }

  @Override
  public String healthCheck(final Check check) throws IOException {

      return execute(new Callable<String>(){
        @Override
        public String call() throws Exception {
          if (check.equals(Check.ACCESS))
            return healthCheckAccess();
          else
            return healthCheckSysinfo();
        }});
  }

  @Override
  public void addComment(final String issueKey, final String comment) throws IOException {

    execute(new Callable<String>() {
	    @Override
	    public String call() throws Exception {
		    log.debug("Adding comment " + comment + " to issue " + issueKey);
		    RemoteComment remoteComment = new RemoteComment();
		    remoteComment.setBody(comment);
		    client().addComment(token, issueKey, remoteComment);
		    log.debug("Added comment " + comment + " to issue " + issueKey);
		    return issueKey;
	    }
    });
  }

  @Override
  public void addRelatedLink(final String issueKey, final URL relatedUrl, String description)
      throws IOException {
    addComment(issueKey, "Related URL: " + createLinkForWebui(relatedUrl.toExternalForm(), description));
  }

	@Override
	public void addRelatedLinkAndComment(String issueId, URL relatedURL, String description, String comment) throws IOException {
		addComment(issueId, createLinkForWebui(relatedURL.toExternalForm(), description) + "\n" + comment);
	}

	@Override
  public void performAction(final String issueKey, final AccountAttribute person, final String actionName)
      throws IOException {

    execute(new Callable<String>(){
      @Override
      public String call() throws Exception {
        doPerformAction(issueKey, person, actionName);
        return issueKey;
      }});
  }

	/**
	 * A state transition is DestinationStatus[|Action|Action|Action]
	 */
	public static class FlowStateTransition {
		final String status;
		final List<String> actions = new ArrayList<>();

		public FlowStateTransition(String flowAction) {
			StringTokenizer st = new StringTokenizer(flowAction, "|");

			status = st.nextToken().trim();

			while (st.hasMoreTokens()) {
				actions.add(st.nextToken().trim().toLowerCase());
			}
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder(status);
			for(String action : actions) {
				sb.append("|").append(action);
			}
			return sb.toString();
		}

		public boolean hasActions() {
			return actions.size() > 0;
		}
	}

	public static class FlowTransition {
		final String start;
		final List<FlowStateTransition> steps = new ArrayList<>();

		public FlowTransition(String start) {
			this.start = start;
		}

		public boolean isEmpty() {
			return steps.size() == 0;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("StartState: " + start);
			for(FlowStateTransition step : steps) {
				sb.append(", goes to -> ").append(step.toString());
			}
			return sb.toString();
		}

		public void addStep(String step) {
			steps.add(new FlowStateTransition(step));
		}
	}

	/**
	 * This allows us to match a condition (e.g. a changeset was created) and be able to determine how to transition
	 * the JIRA issue to where it needs to go, potentially setting recognized actions (e.g. Set-Assigned) in each position.
	 *
	 * Expected to be of the format:
	 * ["CurrentStatus>DestinationStatus,CurrentStatus>DestinationStatus,CurrentStatus>Destination>Destination"]
	 *
	 * Once it has a status, it will go back and check to see if it can do any further transitions, so you would normally
	 * compose transitions. E.g.
	 *
	 * ["Development Done>Review Doing|Set-Assigned,Development Doing>Development Done"]
	 *
	 * will allow it to find its current state is development doing, transition to development done, then see development
	 * done can transition to Review Doing and set the assigned person to the appropriate incoming person.
	 */
	public static class FlowTransitions {
		private Logger log = LoggerFactory.getLogger(getClass());

		private List<FlowTransition> transitions = new ArrayList<>();

		public boolean isEmpty() {
			return transitions.size() == 0;
		}

		public String translateNextActionNameToId(RemoteNamedObject[] statuses, String name) {
			for(RemoteNamedObject status : statuses) {
				if (name.equals(status.getName())) {
					return status.getId();
				}
			}

			return null;
		}

		public FlowTransition findTransitionFlow(String status) {
			for (FlowTransition flow : transitions) {
				if (flow.start.equals(status)) {
					return flow;
				}
			}

			return null;
		}

		public FlowTransitions(String transition) {

			StringTokenizer tokenizer = new StringTokenizer(transition, ",");
			while (tokenizer.hasMoreTokens()) {
				StringTokenizer flow = new StringTokenizer(tokenizer.nextToken(), ">");

				FlowTransition trans = new FlowTransition(flow.nextToken().trim());
				while (flow.hasMoreTokens()) {
					trans.addStep(flow.nextToken().trim());
				}

				if (!trans.isEmpty()) {
					transitions.add(trans);
					log.info("jira transition found: " + trans.toString());
				} else {
					log.error("jira empty action found - no transitions " + transition);
				}
			}
		}
	}

	public String translateStatusFromId(RemoteStatus[] statuses, String id) {
		for(RemoteStatus status : statuses) {
			if (id.equals(status.getId())) {
				return status.getName();
			}
		}

		return null;
	}

	public void doTransition(String transition, JiraClient client, JiraSession token, String issueKey, AccountAttribute person) throws RemoteException {
		log.info("jira matched transition : " + transition);

		FlowTransitions flows = new FlowTransitions(transition);

		if (!flows.isEmpty()) {

			List<String> transitionsPassedThrough = new ArrayList<>();
			boolean finished = false;
			boolean transitionOccured = false;

			while (!finished) {
				RemoteStatus[] statuses = client.service.getStatuses(token.getToken());

				RemoteIssue issue = client.getIssue(token, issueKey);

				// tells us what state we are in, so now we know what transitions we need to make next
				String initialStatus = translateStatusFromId(statuses, issue.getStatus());
				log.info(String.format("Initial status of %s is %s", issueKey, initialStatus));
				FlowTransition flow = flows.findTransitionFlow(initialStatus);

				if (flow != null) {
					for(FlowStateTransition newAction : flow.steps) {
						if (transitionsPassedThrough.contains(newAction.status)) {
							log.error("transition " + transition + " has a cyclical status route in " + flow.toString());
							finished = true;
						} else {
							RemoteNamedObject[] actions = client.getAvailableActions(token, issueKey);

							String destinationId = flows.translateNextActionNameToId(actions, newAction.status);

							if (destinationId != null) {
								transitionOccured = true;

								log.info(String.format("jira transition %s to state %s", issueKey, newAction.status));

								client.performAction(token, issueKey, destinationId);

								if (newAction.hasActions()) {
									RemoteFieldValue[] param = null;

									if (newAction.actions.contains("set-assigned")) {
										param = new RemoteFieldValue[] { new RemoteFieldValue("assignee", new String[] {person.username }) };
										log.info(String.format("jira performing transition on %s to %s assign to user %s", issueKey, newAction.status, person.username));
									} else if (newAction.actions.contains("unassign")) {
										param = new RemoteFieldValue[] { new RemoteFieldValue("assignee", new String[] {}) };
										log.info(String.format("jira performing transition on %s to %s unassign user", issueKey, newAction.status));
									}

									if (param != null) {
										client.service.updateIssue(token.getToken(), issueKey, param);
									}
								} else {
									log.info(String.format("jira performing transition on %s to %s", issueKey, newAction.status));

								}
								transitionsPassedThrough.add(newAction.status);
							} else {
								log.info("jira: Cannot transition to " + newAction + " on transition " + flow.toString());
								finished = true;
							}
						}
					}
				} else {
					finished = true;
				}
			}

			if (!transitionOccured) {
				log.error("Failed to transition, no states.");
			}
		}



	}

  private void doPerformAction(final String issueKey, final AccountAttribute person,  final String actionName)
      throws RemoteException, IOException {
	  doTransition(actionName, client(), token, issueKey, person);
  }


  @Override
  public boolean exists(final String issueKey) throws IOException {
    return execute(new Callable<Boolean>(){
      @Override
      public Boolean call() throws Exception {
        return client().getIssue(token, issueKey) != null;
      }});
  }

  public void logout() {
    this.logout(false);
  }

  public void logout(boolean quiet) {
    try {
      client().logout(token);
    }
    catch (Exception ex) {
      if (!quiet) log.error("I was unable to logout", ex);
    }
  }

  public Object login() {
    return login(false);
  }

  public Object login(boolean quiet) {
    try {
      token = client.login(getUsername(), getPassword());
      log.info("Connected to " + getUrl() + " as " + token);
      return token;
    }
    catch (Exception ex) {
      if (!quiet) {
        log.error("I was unable to logout", ex);
      }

      return null;
    }
  }

  private JiraClient client() throws IOException {

    if (client == null) {
      try {
        log.debug("Connecting to jira at URL " + getUrl());
        client = new JiraClient(getUrl());
        log.debug("Autenthicating as user " + getUsername());
      } catch (Exception ex) {
        log.info("Unable to connect to Connected to " + getUrl() + " as "
            + getUsername());
        throw new IOException(ex);
      }

      login();
    }

    return client;
  }

  private <P> P execute(Callable<P> function) throws IOException {

    int attempt = 0;
    while(true) {
      try {
        return function.call();
      } catch (Exception ex) {
        if (isRecoverable(ex) && ++attempt < MAX_ATTEMPTS) {
          log.debug("Call failed - retrying, attempt {} of {}", attempt, MAX_ATTEMPTS);
          logout(true);
          login(true);
          continue;
        }

        if (ex instanceof IOException)
          throw ((IOException)ex);
        else
          throw new IOException(ex);
      }
    }
  }

  private boolean isRecoverable(Exception ex) {
    if (ex instanceof RemoteAuthenticationException)
      return true;

    String className = ex.getClass().getName();
    if (ex instanceof AxisFault) {
      AxisFault af = (AxisFault)ex;
      className = (af.detail == null ? "unknown" : af.detail.getClass().getName());
    }

    return className.startsWith("java.net");
  }

  private String getPassword() {
    final String pass =
        gerritConfig.getString(pluginName, null,
            GERRIT_CONFIG_PASSWORD);
    return pass;
  }

  private String getUsername() {
    final String user =
        gerritConfig.getString(pluginName, null,
            GERRIT_CONFIG_USERNAME);
    return user;
  }

  private String getUrl() {
    final String url =
        gerritConfig.getString(pluginName, null, GERRIT_CONFIG_URL);
    return url;
  }

  @Override
  public String createLinkForWebui(String url, String text) {
    return "["+text+"|"+url+"]";
  }

  private String healthCheckAccess() throws RemoteException {
    JiraClient client = new JiraClient(getUrl());
    JiraSession token = client.login(getUsername(), getPassword());
    client.logout(token);
    final String result = "{\"status\"=\"ok\",\"username\"=\""+getUsername()+"\"}";
    log.debug("Healtheck on access result: {}", result);
    return result;
  }

  private String healthCheckSysinfo() throws RemoteException, IOException {
    final RemoteServerInfo res = client().getServerInfo(token);
    final String result = "{\"status\"=\"ok\",\"system\"=\"Jira\",\"version\"=\""+res.getVersion()+"\",\"url\"=\""+getUrl()+"\",\"build\"=\""+res.getBuildNumber()+"\"}";
    log.debug("Healtheck on sysinfo result: {}", result);
    return result;
  }
}
