/*
 * Copyright 2011 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.Principal;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.wicket.RequestCycle;
import org.apache.wicket.resource.ContextRelativeResource;
import org.apache.wicket.util.resource.ResourceStreamNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;

import com.gitblit.Constants.AccessPermission;
import com.gitblit.Constants.AccessRestrictionType;
import com.gitblit.Constants.AuthenticationType;
import com.gitblit.Constants.AuthorizationControl;
import com.gitblit.Constants.CommitMessageRenderer;
import com.gitblit.Constants.FederationRequest;
import com.gitblit.Constants.FederationStrategy;
import com.gitblit.Constants.FederationToken;
import com.gitblit.Constants.PermissionType;
import com.gitblit.Constants.RegistrantType;
import com.gitblit.dagger.DaggerContextListener;
import com.gitblit.fanout.FanoutNioService;
import com.gitblit.fanout.FanoutService;
import com.gitblit.fanout.FanoutSocketService;
import com.gitblit.git.GitDaemon;
import com.gitblit.git.GitServlet;
import com.gitblit.manager.IFederationManager;
import com.gitblit.manager.IGitblitManager;
import com.gitblit.manager.IManager;
import com.gitblit.manager.INotificationManager;
import com.gitblit.manager.IProjectManager;
import com.gitblit.manager.IRepositoryManager;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.manager.ISessionManager;
import com.gitblit.manager.IUserManager;
import com.gitblit.models.FederationModel;
import com.gitblit.models.FederationProposal;
import com.gitblit.models.FederationSet;
import com.gitblit.models.ForkModel;
import com.gitblit.models.GitClientApplication;
import com.gitblit.models.Metric;
import com.gitblit.models.ProjectModel;
import com.gitblit.models.RefModel;
import com.gitblit.models.RegistrantAccessPermission;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.RepositoryUrl;
import com.gitblit.models.SearchResult;
import com.gitblit.models.ServerSettings;
import com.gitblit.models.SettingModel;
import com.gitblit.models.TeamModel;
import com.gitblit.models.UserModel;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.Base64;
import com.gitblit.utils.ByteFormat;
import com.gitblit.utils.CommitCache;
import com.gitblit.utils.ContainerUtils;
import com.gitblit.utils.DeepCopier;
import com.gitblit.utils.FederationUtils;
import com.gitblit.utils.HttpUtils;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.JGitUtils.LastChange;
import com.gitblit.utils.JsonUtils;
import com.gitblit.utils.MarkdownUtils;
import com.gitblit.utils.MetricUtils;
import com.gitblit.utils.ModelUtils;
import com.gitblit.utils.ObjectCache;
import com.gitblit.utils.StringUtils;
import com.gitblit.utils.TimeUtils;
import com.gitblit.utils.X509Utils.X509Metadata;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.GitblitWicketFilter;
import com.gitblit.wicket.WicketUtils;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import dagger.ObjectGraph;

/**
 * GitBlit is the servlet context listener singleton that acts as the core for
 * the web ui and the servlets. This class is either directly instantiated by
 * the GitBlitServer class (Gitblit GO) or is reflectively instantiated by the
 * servlet 3 container (Gitblit WAR or Express).
 *
 * This class is the central logic processor for Gitblit. All settings, user
 * object, and repository object operations pass through this class.
 *
 * @author James Moger
 *
 */
@WebListener
public class GitBlit extends DaggerContextListener
					 implements ISessionManager,
								IRepositoryManager,
								IProjectManager,
								IFederationManager,
								IGitblitManager {

	private static GitBlit gitblit;

	private final IStoredSettings goSettings;

	private final File goBaseFolder;

	private final List<IManager> managers = new ArrayList<IManager>();

	private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(10);

	private final List<FederationModel> federationRegistrations = Collections
			.synchronizedList(new ArrayList<FederationModel>());

	private final ObjectCache<Collection<GitClientApplication>> clientApplications = new ObjectCache<Collection<GitClientApplication>>();

	private final Map<String, FederationModel> federationPullResults = new ConcurrentHashMap<String, FederationModel>();

	private final ObjectCache<Long> repositorySizeCache = new ObjectCache<Long>();

	private final ObjectCache<List<Metric>> repositoryMetricsCache = new ObjectCache<List<Metric>>();

	private final Map<String, RepositoryModel> repositoryListCache = new ConcurrentHashMap<String, RepositoryModel>();

	private final Map<String, ProjectModel> projectCache = new ConcurrentHashMap<String, ProjectModel>();

	private final AtomicReference<String> repositoryListSettingsChecksum = new AtomicReference<String>("");

	private final ObjectCache<String> projectMarkdownCache = new ObjectCache<String>();

	private final ObjectCache<String> projectRepositoriesMarkdownCache = new ObjectCache<String>();

	private File repositoriesFolder;

	private IStoredSettings settings;

	private LuceneExecutor luceneExecutor;

	private GCExecutor gcExecutor;

	private MirrorExecutor mirrorExecutor;

	private FileBasedConfig projectConfigs;

	private FanoutService fanoutService;

	private GitDaemon gitDaemon;

	public GitBlit() {
		this.goSettings = null;
		this.goBaseFolder = null;
	}

	public GitBlit(IStoredSettings settings, File baseFolder) {
		this.goSettings = settings;
		this.goBaseFolder = baseFolder;
		gitblit = this;
	}

	/**
	 * Returns the Gitblit singleton.
	 *
	 * @return gitblit singleton
	 */
	public static GitBlit self() {
		return gitblit;
	}

	@SuppressWarnings("unchecked")
	public static <X> X getManager(Class<X> managerClass) {
		if (managerClass.isAssignableFrom(GitBlit.class)) {
			return (X) gitblit;
		}

		for (IManager manager : gitblit.managers) {
			if (managerClass.isAssignableFrom(manager.getClass())) {
				return (X) manager;
			}
		}
		return null;
	}

	/**
	 * Returns the most recent change date of any repository served by Gitblit.
	 *
	 * @return a date
	 */
	@Override
	public Date getLastActivityDate() {
		Date date = null;
		for (String name : getRepositoryList()) {
			Repository r = getRepository(name);
			Date lastChange = JGitUtils.getLastChange(r).when;
			r.close();
			if (lastChange != null && (date == null || lastChange.after(date))) {
				date = lastChange;
			}
		}
		return date;
	}

	/**
	 * Returns the path of the repositories folder. This method checks to see if
	 * Gitblit is running on a cloud service and may return an adjusted path.
	 *
	 * @return the repositories folder path
	 */
	@Override
	public File getRepositoriesFolder() {
		return getManager(IRuntimeManager.class).getFileOrFolder(Keys.git.repositoriesFolder, "${baseFolder}/git");
	}

	/**
	 * Returns the path of the proposals folder. This method checks to see if
	 * Gitblit is running on a cloud service and may return an adjusted path.
	 *
	 * @return the proposals folder path
	 */
	@Override
	public File getProposalsFolder() {
		return getManager(IRuntimeManager.class).getFileOrFolder(Keys.federation.proposalsFolder, "${baseFolder}/proposals");
	}

	/**
	 * Returns the path of the Groovy folder. This method checks to see if
	 * Gitblit is running on a cloud service and may return an adjusted path.
	 *
	 * @return the Groovy scripts folder path
	 */
	@Override
	public File getHooksFolder() {
		return getManager(IRuntimeManager.class).getFileOrFolder(Keys.groovy.scriptsFolder, "${baseFolder}/groovy");
	}

	/**
	 * Returns the path of the Groovy Grape folder. This method checks to see if
	 * Gitblit is running on a cloud service and may return an adjusted path.
	 *
	 * @return the Groovy Grape folder path
	 */
	@Override
	public File getGrapesFolder() {
		return getManager(IRuntimeManager.class).getFileOrFolder(Keys.groovy.grapeFolder, "${baseFolder}/groovy/grape");
	}

	/**
	 * Returns a list of repository URLs and the user access permission.
	 *
	 * @param request
	 * @param user
	 * @param repository
	 * @return a list of repository urls
	 */
	@Override
	public List<RepositoryUrl> getRepositoryUrls(HttpServletRequest request, UserModel user, RepositoryModel repository) {
		if (user == null) {
			user = UserModel.ANONYMOUS;
		}
		String username = StringUtils.encodeUsername(UserModel.ANONYMOUS.equals(user) ? "" : user.username);

		List<RepositoryUrl> list = new ArrayList<RepositoryUrl>();
		// http/https url
		if (settings.getBoolean(Keys.git.enableGitServlet, true)) {
			AccessPermission permission = user.getRepositoryPermission(repository).permission;
			if (permission.exceeds(AccessPermission.NONE)) {
				list.add(new RepositoryUrl(getRepositoryUrl(request, username, repository), permission));
			}
		}

		// git daemon url
		String gitDaemonUrl = getGitDaemonUrl(request, user, repository);
		if (!StringUtils.isEmpty(gitDaemonUrl)) {
			AccessPermission permission = getGitDaemonAccessPermission(user, repository);
			if (permission.exceeds(AccessPermission.NONE)) {
				list.add(new RepositoryUrl(gitDaemonUrl, permission));
			}
		}

		// add all other urls
		// {0} = repository
		// {1} = username
		for (String url : settings.getStrings(Keys.web.otherUrls)) {
			if (url.contains("{1}")) {
				// external url requires username, only add url IF we have one
				if(!StringUtils.isEmpty(username)) {
					list.add(new RepositoryUrl(MessageFormat.format(url, repository.name, username), null));
				}
			} else {
				// external url does not require username
				list.add(new RepositoryUrl(MessageFormat.format(url, repository.name), null));
			}
		}
		return list;
	}

	protected String getRepositoryUrl(HttpServletRequest request, String username, RepositoryModel repository) {
		StringBuilder sb = new StringBuilder();
		sb.append(HttpUtils.getGitblitURL(request));
		sb.append(Constants.GIT_PATH);
		sb.append(repository.name);

		// inject username into repository url if authentication is required
		if (repository.accessRestriction.exceeds(AccessRestrictionType.NONE)
				&& !StringUtils.isEmpty(username)) {
			sb.insert(sb.indexOf("://") + 3, username + "@");
		}
		return sb.toString();
	}

	protected String getGitDaemonUrl(HttpServletRequest request, UserModel user, RepositoryModel repository) {
		if (gitDaemon != null) {
			String bindInterface = settings.getString(Keys.git.daemonBindInterface, "localhost");
			if (bindInterface.equals("localhost")
					&& (!request.getServerName().equals("localhost") && !request.getServerName().equals("127.0.0.1"))) {
				// git daemon is bound to localhost and the request is from elsewhere
				return null;
			}
			if (user.canClone(repository)) {
				String servername = request.getServerName();
				String url = gitDaemon.formatUrl(servername, repository.name);
				return url;
			}
		}
		return null;
	}

	protected AccessPermission getGitDaemonAccessPermission(UserModel user, RepositoryModel repository) {
		if (gitDaemon != null && user.canClone(repository)) {
			AccessPermission gitDaemonPermission = user.getRepositoryPermission(repository).permission;
			if (gitDaemonPermission.atLeast(AccessPermission.CLONE)) {
				if (repository.accessRestriction.atLeast(AccessRestrictionType.CLONE)) {
					// can not authenticate clone via anonymous git protocol
					gitDaemonPermission = AccessPermission.NONE;
				} else if (repository.accessRestriction.atLeast(AccessRestrictionType.PUSH)) {
					// can not authenticate push via anonymous git protocol
					gitDaemonPermission = AccessPermission.CLONE;
				} else {
					// normal user permission
				}
			}
			return gitDaemonPermission;
		}
		return AccessPermission.NONE;
	}

	/**
	 * Returns the list of custom client applications to be used for the
	 * repository url panel;
	 *
	 * @return a collection of client applications
	 */
	@Override
	public Collection<GitClientApplication> getClientApplications() {
		// prefer user definitions, if they exist
		File userDefs = new File(getManager(IRuntimeManager.class).getBaseFolder(), "clientapps.json");
		if (userDefs.exists()) {
			Date lastModified = new Date(userDefs.lastModified());
			if (clientApplications.hasCurrent("user", lastModified)) {
				return clientApplications.getObject("user");
			} else {
				// (re)load user definitions
				try {
					InputStream is = new FileInputStream(userDefs);
					Collection<GitClientApplication> clients = readClientApplications(is);
					is.close();
					if (clients != null) {
						clientApplications.updateObject("user", lastModified, clients);
						return clients;
					}
				} catch (IOException e) {
					logger.error("Failed to deserialize " + userDefs.getAbsolutePath(), e);
				}
			}
		}

		// no user definitions, use system definitions
		if (!clientApplications.hasCurrent("system", new Date(0))) {
			try {
				InputStream is = getClass().getResourceAsStream("/clientapps.json");
				Collection<GitClientApplication> clients = readClientApplications(is);
				is.close();
				if (clients != null) {
					clientApplications.updateObject("system", new Date(0), clients);
				}
			} catch (IOException e) {
				logger.error("Failed to deserialize clientapps.json resource!", e);
			}
		}

		return clientApplications.getObject("system");
	}

	private Collection<GitClientApplication> readClientApplications(InputStream is) {
		try {
			Type type = new TypeToken<Collection<GitClientApplication>>() {
			}.getType();
			InputStreamReader reader = new InputStreamReader(is);
			Gson gson = JsonUtils.gson();
			Collection<GitClientApplication> links = gson.fromJson(reader, type);
			return links;
		} catch (JsonIOException e) {
			logger.error("Error deserializing client applications!", e);
		} catch (JsonSyntaxException e) {
			logger.error("Error deserializing client applications!", e);
		}
		return null;
	}

	/**
	 * Returns true if the username represents an internal account
	 *
	 * @param username
	 * @return true if the specified username represents an internal account
	 */
	protected boolean isInternalAccount(String username) {
		return !StringUtils.isEmpty(username)
				&& (username.equalsIgnoreCase(Constants.FEDERATION_USER)
						|| username.equalsIgnoreCase(UserModel.ANONYMOUS.username));
	}

	/**
	 * Authenticate a user based on a username and password.
	 *
	 * @see IUserService.authenticate(String, char[])
	 * @param username
	 * @param password
	 * @return a user object or null
	 */
	@Override
	public UserModel authenticate(String username, char[] password) {
		if (StringUtils.isEmpty(username)) {
			// can not authenticate empty username
			return null;
		}
		String usernameDecoded = StringUtils.decodeUsername(username);
		String pw = new String(password);
		if (StringUtils.isEmpty(pw)) {
			// can not authenticate empty password
			return null;
		}

		// check to see if this is the federation user
		if (canFederate()) {
			if (usernameDecoded.equalsIgnoreCase(Constants.FEDERATION_USER)) {
				List<String> tokens = getFederationTokens();
				if (tokens.contains(pw)) {
					return getFederationUser();
				}
			}
		}

		// delegate authentication to the user service
		return getManager(IUserManager.class).authenticate(usernameDecoded, password);
	}

	/**
	 * Authenticate a user based on their cookie.
	 *
	 * @param cookies
	 * @return a user object or null
	 */
	protected UserModel authenticate(Cookie[] cookies) {
		if (getManager(IUserManager.class).supportsCookies()) {
			if (cookies != null && cookies.length > 0) {
				for (Cookie cookie : cookies) {
					if (cookie.getName().equals(Constants.NAME)) {
						String value = cookie.getValue();
						return getManager(IUserManager.class).authenticate(value.toCharArray());
					}
				}
			}
		}
		return null;
	}

	/**
	 * Authenticate a user based on HTTP request parameters.
	 *
	 * Authentication by X509Certificate is tried first and then by cookie.
	 *
	 * @param httpRequest
	 * @return a user object or null
	 */
	@Override
	public UserModel authenticate(HttpServletRequest httpRequest) {
		return authenticate(httpRequest, false);
	}

	/**
	 * Authenticate a user based on HTTP request parameters.
	 *
	 * Authentication by X509Certificate, servlet container principal, cookie,
	 * and BASIC header.
	 *
	 * @param httpRequest
	 * @param requiresCertificate
	 * @return a user object or null
	 */
	@Override
	public UserModel authenticate(HttpServletRequest httpRequest, boolean requiresCertificate) {
		// try to authenticate by certificate
		boolean checkValidity = settings.getBoolean(Keys.git.enforceCertificateValidity, true);
		String [] oids = settings.getStrings(Keys.git.certificateUsernameOIDs).toArray(new String[0]);
		UserModel model = HttpUtils.getUserModelFromCertificate(httpRequest, checkValidity, oids);
		if (model != null) {
			// grab real user model and preserve certificate serial number
			UserModel user = getManager(IUserManager.class).getUserModel(model.username);
			X509Metadata metadata = HttpUtils.getCertificateMetadata(httpRequest);
			if (user != null) {
				flagWicketSession(AuthenticationType.CERTIFICATE);
				logger.debug(MessageFormat.format("{0} authenticated by client certificate {1} from {2}",
						user.username, metadata.serialNumber, httpRequest.getRemoteAddr()));
				return user;
			} else {
				logger.warn(MessageFormat.format("Failed to find UserModel for {0}, attempted client certificate ({1}) authentication from {2}",
						model.username, metadata.serialNumber, httpRequest.getRemoteAddr()));
			}
		}

		if (requiresCertificate) {
			// caller requires client certificate authentication (e.g. git servlet)
			return null;
		}

		// try to authenticate by servlet container principal
		Principal principal = httpRequest.getUserPrincipal();
		if (principal != null) {
			String username = principal.getName();
			if (!StringUtils.isEmpty(username)) {
				boolean internalAccount = isInternalAccount(username);
				UserModel user = getManager(IUserManager.class).getUserModel(username);
				if (user != null) {
					// existing user
					flagWicketSession(AuthenticationType.CONTAINER);
					logger.debug(MessageFormat.format("{0} authenticated by servlet container principal from {1}",
							user.username, httpRequest.getRemoteAddr()));
					return user;
				} else if (settings.getBoolean(Keys.realm.container.autoCreateAccounts, false)
						&& !internalAccount) {
					// auto-create user from an authenticated container principal
					user = new UserModel(username.toLowerCase());
					user.displayName = username;
					user.password = Constants.EXTERNAL_ACCOUNT;
					getManager(IUserManager.class).updateUserModel(user);
					flagWicketSession(AuthenticationType.CONTAINER);
					logger.debug(MessageFormat.format("{0} authenticated and created by servlet container principal from {1}",
							user.username, httpRequest.getRemoteAddr()));
					return user;
				} else if (!internalAccount) {
					logger.warn(MessageFormat.format("Failed to find UserModel for {0}, attempted servlet container authentication from {1}",
							principal.getName(), httpRequest.getRemoteAddr()));
				}
			}
		}

		// try to authenticate by cookie
		if (getManager(IUserManager.class).supportsCookies()) {
			UserModel user = authenticate(httpRequest.getCookies());
			if (user != null) {
				flagWicketSession(AuthenticationType.COOKIE);
				logger.debug(MessageFormat.format("{0} authenticated by cookie from {1}",
						user.username, httpRequest.getRemoteAddr()));
				return user;
			}
		}

		// try to authenticate by BASIC
		final String authorization = httpRequest.getHeader("Authorization");
		if (authorization != null && authorization.startsWith("Basic")) {
			// Authorization: Basic base64credentials
			String base64Credentials = authorization.substring("Basic".length()).trim();
			String credentials = new String(Base64.decode(base64Credentials),
					Charset.forName("UTF-8"));
			// credentials = username:password
			final String[] values = credentials.split(":", 2);

			if (values.length == 2) {
				String username = values[0];
				char[] password = values[1].toCharArray();
				UserModel user = authenticate(username, password);
				if (user != null) {
					flagWicketSession(AuthenticationType.CREDENTIALS);
					logger.debug(MessageFormat.format("{0} authenticated by BASIC request header from {1}",
							user.username, httpRequest.getRemoteAddr()));
					return user;
				} else {
					logger.warn(MessageFormat.format("Failed login attempt for {0}, invalid credentials from {1}",
							username, httpRequest.getRemoteAddr()));
				}
			}
		}
		return null;
	}

	protected void flagWicketSession(AuthenticationType authenticationType) {
		RequestCycle requestCycle = RequestCycle.get();
		if (requestCycle != null) {
			// flag the Wicket session, if this is a Wicket request
			GitBlitWebSession session = GitBlitWebSession.get();
			session.authenticationType = authenticationType;
		}
	}

	/**
	 * Open a file resource using the Servlet container.
	 * @param file to open
	 * @return InputStream of the opened file
	 * @throws ResourceStreamNotFoundException
	 */
	public InputStream getResourceAsStream(String file) throws ResourceStreamNotFoundException {
		ContextRelativeResource res = WicketUtils.getResource(file);
		return res.getResourceStream().getInputStream();
	}

	/**
	 * Sets a cookie for the specified user.
	 *
	 * @param response
	 * @param user
	 */
	@Override
	public void setCookie(HttpServletResponse response, UserModel user) {
		GitBlitWebSession session = GitBlitWebSession.get();
		boolean standardLogin = session.authenticationType.isStandard();

		if (getManager(IUserManager.class).supportsCookies() && standardLogin) {
			Cookie userCookie;
			if (user == null) {
				// clear cookie for logout
				userCookie = new Cookie(Constants.NAME, "");
			} else {
				// set cookie for login
				String cookie = getManager(IUserManager.class).getCookie(user);
				if (StringUtils.isEmpty(cookie)) {
					// create empty cookie
					userCookie = new Cookie(Constants.NAME, "");
				} else {
					// create real cookie
					userCookie = new Cookie(Constants.NAME, cookie);
					userCookie.setMaxAge(Integer.MAX_VALUE);
				}
			}
			userCookie.setPath("/");
			response.addCookie(userCookie);
		}
	}

	@Override
	public UserModel getFederationUser() {
		// the federation user is an administrator
		UserModel federationUser = new UserModel(Constants.FEDERATION_USER);
		federationUser.canAdmin = true;
		return federationUser;
	}

	/**
	 * Returns the effective list of permissions for this user, taking into account
	 * team memberships, ownerships.
	 *
	 * @param user
	 * @return the effective list of permissions for the user
	 */
	@Override
	public List<RegistrantAccessPermission> getUserAccessPermissions(UserModel user) {
		if (StringUtils.isEmpty(user.username)) {
			// new user
			return new ArrayList<RegistrantAccessPermission>();
		}
		Set<RegistrantAccessPermission> set = new LinkedHashSet<RegistrantAccessPermission>();
		set.addAll(user.getRepositoryPermissions());
		// Flag missing repositories
		for (RegistrantAccessPermission permission : set) {
			if (permission.mutable && PermissionType.EXPLICIT.equals(permission.permissionType)) {
				RepositoryModel rm = getRepositoryModel(permission.registrant);
				if (rm == null) {
					permission.permissionType = PermissionType.MISSING;
					permission.mutable = false;
					continue;
				}
			}
		}

		// TODO reconsider ownership as a user property
		// manually specify personal repository ownerships
		for (RepositoryModel rm : repositoryListCache.values()) {
			if (rm.isUsersPersonalRepository(user.username) || rm.isOwner(user.username)) {
				RegistrantAccessPermission rp = new RegistrantAccessPermission(rm.name, AccessPermission.REWIND,
						PermissionType.OWNER, RegistrantType.REPOSITORY, null, false);
				// user may be owner of a repository to which they've inherited
				// a team permission, replace any existing perm with owner perm
				set.remove(rp);
				set.add(rp);
			}
		}

		List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>(set);
		Collections.sort(list);
		return list;
	}

	/**
	 * Returns the list of users and their access permissions for the specified
	 * repository including permission source information such as the team or
	 * regular expression which sets the permission.
	 *
	 * @param repository
	 * @return a list of RegistrantAccessPermissions
	 */
	@Override
	public List<RegistrantAccessPermission> getUserAccessPermissions(RepositoryModel repository) {
		List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>();
		if (AccessRestrictionType.NONE.equals(repository.accessRestriction)) {
			// no permissions needed, REWIND for everyone!
			return list;
		}
		if (AuthorizationControl.AUTHENTICATED.equals(repository.authorizationControl)) {
			// no permissions needed, REWIND for authenticated!
			return list;
		}
		// NAMED users and teams
		for (UserModel user : getManager(IUserManager.class).getAllUsers()) {
			RegistrantAccessPermission ap = user.getRepositoryPermission(repository);
			if (ap.permission.exceeds(AccessPermission.NONE)) {
				list.add(ap);
			}
		}
		return list;
	}

	/**
	 * Sets the access permissions to the specified repository for the specified users.
	 *
	 * @param repository
	 * @param permissions
	 * @return true if the user models have been updated
	 */
	@Override
	public boolean setUserAccessPermissions(RepositoryModel repository, Collection<RegistrantAccessPermission> permissions) {
		List<UserModel> users = new ArrayList<UserModel>();
		for (RegistrantAccessPermission up : permissions) {
			if (up.mutable) {
				// only set editable defined permissions
				UserModel user = getManager(IUserManager.class).getUserModel(up.registrant);
				user.setRepositoryPermission(repository.name, up.permission);
				users.add(user);
			}
		}
		return getManager(IUserManager.class).updateUserModels(users);
	}

	/**
	 * Returns the list of all users who have an explicit access permission
	 * for the specified repository.
	 *
	 * @see IUserService.getUsernamesForRepositoryRole(String)
	 * @param repository
	 * @return list of all usernames that have an access permission for the repository
	 */
	@Override
	public List<String> getRepositoryUsers(RepositoryModel repository) {
		return getManager(IUserManager.class).getUsernamesForRepositoryRole(repository.name);
	}

	/**
	 * Sets the list of all uses who are allowed to bypass the access
	 * restriction placed on the specified repository.
	 *
	 * @see IUserService.setUsernamesForRepositoryRole(String, List<String>)
	 * @param repository
	 * @param usernames
	 * @return true if successful
	 */
	@Deprecated
	public boolean setRepositoryUsers(RepositoryModel repository, List<String> repositoryUsers) {
		// rejects all changes since 1.2.0 because this would elevate
		// all discrete access permissions to RW+
		return false;
	}

	/**
	 * Adds/updates a complete user object keyed by username. This method allows
	 * for renaming a user.
	 *
	 * @see IUserService.updateUserModel(String, UserModel)
	 * @param username
	 * @param user
	 * @param isCreate
	 * @throws GitBlitException
	 */
	@Override
	public void updateUserModel(String username, UserModel user, boolean isCreate)
			throws GitBlitException {
		if (!username.equalsIgnoreCase(user.username)) {
			if (getManager(IUserManager.class).getUserModel(user.username) != null) {
				throw new GitBlitException(MessageFormat.format(
						"Failed to rename ''{0}'' because ''{1}'' already exists.", username,
						user.username));
			}

			// rename repositories and owner fields for all repositories
			for (RepositoryModel model : getRepositoryModels(user)) {
				if (model.isUsersPersonalRepository(username)) {
					// personal repository
					model.addOwner(user.username);
					String oldRepositoryName = model.name;
					model.name = user.getPersonalPath() + model.name.substring(model.projectPath.length());
					model.projectPath = user.getPersonalPath();
					updateRepositoryModel(oldRepositoryName, model, false);
				} else if (model.isOwner(username)) {
					// common/shared repo
					model.addOwner(user.username);
					updateRepositoryModel(model.name, model, false);
				}
			}
		}
		if (!getManager(IUserManager.class).updateUserModel(username, user)) {
			throw new GitBlitException(isCreate ? "Failed to add user!" : "Failed to update user!");
		}
	}

	/**
	 * Returns the list of teams and their access permissions for the specified
	 * repository including the source of the permission such as the admin flag
	 * or a regular expression.
	 *
	 * @param repository
	 * @return a list of RegistrantAccessPermissions
	 */
	@Override
	public List<RegistrantAccessPermission> getTeamAccessPermissions(RepositoryModel repository) {
		List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>();
		for (TeamModel team : getManager(IUserManager.class).getAllTeams()) {
			RegistrantAccessPermission ap = team.getRepositoryPermission(repository);
			if (ap.permission.exceeds(AccessPermission.NONE)) {
				list.add(ap);
			}
		}
		Collections.sort(list);
		return list;
	}

	/**
	 * Sets the access permissions to the specified repository for the specified teams.
	 *
	 * @param repository
	 * @param permissions
	 * @return true if the team models have been updated
	 */
	@Override
	public boolean setTeamAccessPermissions(RepositoryModel repository, Collection<RegistrantAccessPermission> permissions) {
		List<TeamModel> teams = new ArrayList<TeamModel>();
		for (RegistrantAccessPermission tp : permissions) {
			if (tp.mutable) {
				// only set explicitly defined access permissions
				TeamModel team = getManager(IUserManager.class).getTeamModel(tp.registrant);
				team.setRepositoryPermission(repository.name, tp.permission);
				teams.add(team);
			}
		}
		return getManager(IUserManager.class).updateTeamModels(teams);
	}

	/**
	 * Returns the list of all teams who have an explicit access permission for
	 * the specified repository.
	 *
	 * @see IUserService.getTeamnamesForRepositoryRole(String)
	 * @param repository
	 * @return list of all teamnames with explicit access permissions to the repository
	 */
	@Override
	public List<String> getRepositoryTeams(RepositoryModel repository) {
		return getManager(IUserManager.class).getTeamNamesForRepositoryRole(repository.name);
	}

	/**
	 * Sets the list of all uses who are allowed to bypass the access
	 * restriction placed on the specified repository.
	 *
	 * @see IUserService.setTeamnamesForRepositoryRole(String, List<String>)
	 * @param repository
	 * @param teamnames
	 * @return true if successful
	 */
	@Deprecated
	public boolean setRepositoryTeams(RepositoryModel repository, List<String> repositoryTeams) {
		// rejects all changes since 1.2.0 because this would elevate
		// all discrete access permissions to RW+
		return false;
	}

	/**
	 * Updates the TeamModel object for the specified name.
	 *
	 * @param teamname
	 * @param team
	 * @param isCreate
	 */
	@Override
	public void updateTeamModel(String teamname, TeamModel team, boolean isCreate)
			throws GitBlitException {
		if (!teamname.equalsIgnoreCase(team.name)) {
			if (getManager(IUserManager.class).getTeamModel(team.name) != null) {
				throw new GitBlitException(MessageFormat.format(
						"Failed to rename ''{0}'' because ''{1}'' already exists.", teamname,
						team.name));
			}
		}
		if (!getManager(IUserManager.class).updateTeamModel(teamname, team)) {
			throw new GitBlitException(isCreate ? "Failed to add team!" : "Failed to update team!");
		}
	}

	/**
	 * Adds the repository to the list of cached repositories if Gitblit is
	 * configured to cache the repository list.
	 *
	 * @param model
	 */
	@Override
	public void addToCachedRepositoryList(RepositoryModel model) {
		if (settings.getBoolean(Keys.git.cacheRepositoryList, true)) {
			repositoryListCache.put(model.name.toLowerCase(), model);

			// update the fork origin repository with this repository clone
			if (!StringUtils.isEmpty(model.originRepository)) {
				if (repositoryListCache.containsKey(model.originRepository)) {
					RepositoryModel origin = repositoryListCache.get(model.originRepository);
					origin.addFork(model.name);
				}
			}
		}
	}

	/**
	 * Removes the repository from the list of cached repositories.
	 *
	 * @param name
	 * @return the model being removed
	 */
	private RepositoryModel removeFromCachedRepositoryList(String name) {
		if (StringUtils.isEmpty(name)) {
			return null;
		}
		return repositoryListCache.remove(name.toLowerCase());
	}

	/**
	 * Clears all the cached metadata for the specified repository.
	 *
	 * @param repositoryName
	 */
	private void clearRepositoryMetadataCache(String repositoryName) {
		repositorySizeCache.remove(repositoryName);
		repositoryMetricsCache.remove(repositoryName);
	}

	/**
	 * Resets the repository list cache.
	 *
	 */
	@Override
	public void resetRepositoryListCache() {
		logger.info("Repository cache manually reset");
		repositoryListCache.clear();
	}

	/**
	 * Calculate the checksum of settings that affect the repository list cache.
	 * @return a checksum
	 */
	private String getRepositoryListSettingsChecksum() {
		StringBuilder ns = new StringBuilder();
		ns.append(settings.getString(Keys.git.cacheRepositoryList, "")).append('\n');
		ns.append(settings.getString(Keys.git.onlyAccessBareRepositories, "")).append('\n');
		ns.append(settings.getString(Keys.git.searchRepositoriesSubfolders, "")).append('\n');
		ns.append(settings.getString(Keys.git.searchRecursionDepth, "")).append('\n');
		ns.append(settings.getString(Keys.git.searchExclusions, "")).append('\n');
		String checksum = StringUtils.getSHA1(ns.toString());
		return checksum;
	}

	/**
	 * Compare the last repository list setting checksum to the current checksum.
	 * If different then clear the cache so that it may be rebuilt.
	 *
	 * @return true if the cached repository list is valid since the last check
	 */
	private boolean isValidRepositoryList() {
		String newChecksum = getRepositoryListSettingsChecksum();
		boolean valid = newChecksum.equals(repositoryListSettingsChecksum.get());
		repositoryListSettingsChecksum.set(newChecksum);
		if (!valid && settings.getBoolean(Keys.git.cacheRepositoryList,  true)) {
			logger.info("Repository list settings have changed. Clearing repository list cache.");
			repositoryListCache.clear();
		}
		return valid;
	}

	/**
	 * Returns the list of all repositories available to Gitblit. This method
	 * does not consider user access permissions.
	 *
	 * @return list of all repositories
	 */
	@Override
	public List<String> getRepositoryList() {
		if (repositoryListCache.size() == 0 || !isValidRepositoryList()) {
			// we are not caching OR we have not yet cached OR the cached list is invalid
			long startTime = System.currentTimeMillis();
			List<String> repositories = JGitUtils.getRepositoryList(repositoriesFolder,
					settings.getBoolean(Keys.git.onlyAccessBareRepositories, false),
					settings.getBoolean(Keys.git.searchRepositoriesSubfolders, true),
					settings.getInteger(Keys.git.searchRecursionDepth, -1),
					settings.getStrings(Keys.git.searchExclusions));

			if (!settings.getBoolean(Keys.git.cacheRepositoryList,  true)) {
				// we are not caching
				StringUtils.sortRepositorynames(repositories);
				return repositories;
			} else {
				// we are caching this list
				String msg = "{0} repositories identified in {1} msecs";
				if (settings.getBoolean(Keys.web.showRepositorySizes, true)) {
					// optionally (re)calculate repository sizes
					msg = "{0} repositories identified with calculated folder sizes in {1} msecs";
				}

				for (String repository : repositories) {
					getRepositoryModel(repository);
				}

				// rebuild fork networks
				for (RepositoryModel model : repositoryListCache.values()) {
					if (!StringUtils.isEmpty(model.originRepository)) {
						if (repositoryListCache.containsKey(model.originRepository)) {
							RepositoryModel origin = repositoryListCache.get(model.originRepository);
							origin.addFork(model.name);
						}
					}
				}

				long duration = System.currentTimeMillis() - startTime;
				logger.info(MessageFormat.format(msg, repositoryListCache.size(), duration));
			}
		}

		// return sorted copy of cached list
		List<String> list = new ArrayList<String>();
		for (RepositoryModel model : repositoryListCache.values()) {
			list.add(model.name);
		}
		StringUtils.sortRepositorynames(list);
		return list;
	}

	/**
	 * Returns the JGit repository for the specified name.
	 *
	 * @param repositoryName
	 * @return repository or null
	 */
	@Override
	public Repository getRepository(String repositoryName) {
		return getRepository(repositoryName, true);
	}

	/**
	 * Returns the JGit repository for the specified name.
	 *
	 * @param repositoryName
	 * @param logError
	 * @return repository or null
	 */
	@Override
	public Repository getRepository(String repositoryName, boolean logError) {
		// Decode url-encoded repository name (issue-278)
		// http://stackoverflow.com/questions/17183110
		repositoryName = repositoryName.replace("%7E", "~").replace("%7e", "~");

		if (isCollectingGarbage(repositoryName)) {
			logger.warn(MessageFormat.format("Rejecting request for {0}, busy collecting garbage!", repositoryName));
			return null;
		}

		File dir = FileKey.resolve(new File(repositoriesFolder, repositoryName), FS.DETECTED);
		if (dir == null)
			return null;

		Repository r = null;
		try {
			FileKey key = FileKey.exact(dir, FS.DETECTED);
			r = RepositoryCache.open(key, true);
		} catch (IOException e) {
			if (logError) {
				logger.error("GitBlit.getRepository(String) failed to find "
						+ new File(repositoriesFolder, repositoryName).getAbsolutePath());
			}
		}
		return r;
	}

	/**
	 * Returns the list of repository models that are accessible to the user.
	 *
	 * @param user
	 * @return list of repository models accessible to user
	 */
	@Override
	public List<RepositoryModel> getRepositoryModels(UserModel user) {
		long methodStart = System.currentTimeMillis();
		List<String> list = getRepositoryList();
		List<RepositoryModel> repositories = new ArrayList<RepositoryModel>();
		for (String repo : list) {
			RepositoryModel model = getRepositoryModel(user, repo);
			if (model != null) {
				if (!model.hasCommits) {
					// only add empty repositories that user can push to
					if (UserModel.ANONYMOUS.canPush(model)
							|| user != null && user.canPush(model)) {
						repositories.add(model);
					}
				} else {
					repositories.add(model);
				}
			}
		}
		long duration = System.currentTimeMillis() - methodStart;
		logger.info(MessageFormat.format("{0} repository models loaded for {1} in {2} msecs",
				repositories.size(), user == null ? "anonymous" : user.username, duration));
		return repositories;
	}

	/**
	 * Returns a repository model if the repository exists and the user may
	 * access the repository.
	 *
	 * @param user
	 * @param repositoryName
	 * @return repository model or null
	 */
	@Override
	public RepositoryModel getRepositoryModel(UserModel user, String repositoryName) {
		RepositoryModel model = getRepositoryModel(repositoryName);
		if (model == null) {
			return null;
		}
		if (user == null) {
			user = UserModel.ANONYMOUS;
		}
		if (user.canView(model)) {
			return model;
		}
		return null;
	}

	/**
	 * Returns the repository model for the specified repository. This method
	 * does not consider user access permissions.
	 *
	 * @param repositoryName
	 * @return repository model or null
	 */
	@Override
	public RepositoryModel getRepositoryModel(String repositoryName) {
		// Decode url-encoded repository name (issue-278)
		// http://stackoverflow.com/questions/17183110
		repositoryName = repositoryName.replace("%7E", "~").replace("%7e", "~");

		if (!repositoryListCache.containsKey(repositoryName)) {
			RepositoryModel model = loadRepositoryModel(repositoryName);
			if (model == null) {
				return null;
			}
			addToCachedRepositoryList(model);
			return DeepCopier.copy(model);
		}

		// cached model
		RepositoryModel model = repositoryListCache.get(repositoryName.toLowerCase());

		if (gcExecutor.isCollectingGarbage(model.name)) {
			// Gitblit is busy collecting garbage, use our cached model
			RepositoryModel rm = DeepCopier.copy(model);
			rm.isCollectingGarbage = true;
			return rm;
		}

		// check for updates
		Repository r = getRepository(model.name);
		if (r == null) {
			// repository is missing
			removeFromCachedRepositoryList(repositoryName);
			logger.error(MessageFormat.format("Repository \"{0}\" is missing! Removing from cache.", repositoryName));
			return null;
		}

		FileBasedConfig config = (FileBasedConfig) getRepositoryConfig(r);
		if (config.isOutdated()) {
			// reload model
			logger.debug(MessageFormat.format("Config for \"{0}\" has changed. Reloading model and updating cache.", repositoryName));
			model = loadRepositoryModel(model.name);
			removeFromCachedRepositoryList(model.name);
			addToCachedRepositoryList(model);
		} else {
			// update a few repository parameters
			if (!model.hasCommits) {
				// update hasCommits, assume a repository only gains commits :)
				model.hasCommits = JGitUtils.hasCommits(r);
			}

			updateLastChangeFields(r, model);
		}
		r.close();

		// return a copy of the cached model
		return DeepCopier.copy(model);
	}

	/**
	 * Returns the star count of the repository.
	 *
	 * @param repository
	 * @return the star count
	 */
	@Override
	public long getStarCount(RepositoryModel repository) {
		long count = 0;
		for (UserModel user : getManager(IUserManager.class).getAllUsers()) {
			if (user.getPreferences().isStarredRepository(repository.name)) {
				count++;
			}
		}
		return count;
	}

	private void reloadProjectMarkdown(ProjectModel project) {
		// project markdown
		File pmkd = new File(getRepositoriesFolder(), (project.isRoot ? "" : project.name) + "/project.mkd");
		if (pmkd.exists()) {
			Date lm = new Date(pmkd.lastModified());
			if (!projectMarkdownCache.hasCurrent(project.name, lm)) {
				String mkd = com.gitblit.utils.FileUtils.readContent(pmkd,  "\n");
				projectMarkdownCache.updateObject(project.name, lm, mkd);
			}
			project.projectMarkdown = projectMarkdownCache.getObject(project.name);
		}

		// project repositories markdown
		File rmkd = new File(getRepositoriesFolder(), (project.isRoot ? "" : project.name) + "/repositories.mkd");
		if (rmkd.exists()) {
			Date lm = new Date(rmkd.lastModified());
			if (!projectRepositoriesMarkdownCache.hasCurrent(project.name, lm)) {
				String mkd = com.gitblit.utils.FileUtils.readContent(rmkd,  "\n");
				projectRepositoriesMarkdownCache.updateObject(project.name, lm, mkd);
			}
			project.repositoriesMarkdown = projectRepositoriesMarkdownCache.getObject(project.name);
		}
	}


	/**
	 * Returns the map of project config.  This map is cached and reloaded if
	 * the underlying projects.conf file changes.
	 *
	 * @return project config map
	 */
	private Map<String, ProjectModel> getProjectConfigs() {
		if (projectCache.isEmpty() || projectConfigs.isOutdated()) {

			try {
				projectConfigs.load();
			} catch (Exception e) {
			}

			// project configs
			String rootName = settings.getString(Keys.web.repositoryRootGroupName, "main");
			ProjectModel rootProject = new ProjectModel(rootName, true);

			Map<String, ProjectModel> configs = new HashMap<String, ProjectModel>();
			// cache the root project under its alias and an empty path
			configs.put("", rootProject);
			configs.put(rootProject.name.toLowerCase(), rootProject);

			for (String name : projectConfigs.getSubsections("project")) {
				ProjectModel project;
				if (name.equalsIgnoreCase(rootName)) {
					project = rootProject;
				} else {
					project = new ProjectModel(name);
				}
				project.title = projectConfigs.getString("project", name, "title");
				project.description = projectConfigs.getString("project", name, "description");

				reloadProjectMarkdown(project);

				configs.put(name.toLowerCase(), project);
			}
			projectCache.clear();
			projectCache.putAll(configs);
		}
		return projectCache;
	}

	/**
	 * Returns a list of project models for the user.
	 *
	 * @param user
	 * @param includeUsers
	 * @return list of projects that are accessible to the user
	 */
	@Override
	public List<ProjectModel> getProjectModels(UserModel user, boolean includeUsers) {
		Map<String, ProjectModel> configs = getProjectConfigs();

		// per-user project lists, this accounts for security and visibility
		Map<String, ProjectModel> map = new TreeMap<String, ProjectModel>();
		// root project
		map.put("", configs.get(""));

		for (RepositoryModel model : getRepositoryModels(user)) {
			String rootPath = StringUtils.getRootPath(model.name).toLowerCase();
			if (!map.containsKey(rootPath)) {
				ProjectModel project;
				if (configs.containsKey(rootPath)) {
					// clone the project model because it's repository list will
					// be tailored for the requesting user
					project = DeepCopier.copy(configs.get(rootPath));
				} else {
					project = new ProjectModel(rootPath);
				}
				map.put(rootPath, project);
			}
			map.get(rootPath).addRepository(model);
		}

		// sort projects, root project first
		List<ProjectModel> projects;
		if (includeUsers) {
			// all projects
			projects = new ArrayList<ProjectModel>(map.values());
			Collections.sort(projects);
			projects.remove(map.get(""));
			projects.add(0, map.get(""));
		} else {
			// all non-user projects
			projects = new ArrayList<ProjectModel>();
			ProjectModel root = map.remove("");
			for (ProjectModel model : map.values()) {
				if (!model.isUserProject()) {
					projects.add(model);
				}
			}
			Collections.sort(projects);
			projects.add(0, root);
		}
		return projects;
	}

	/**
	 * Returns the project model for the specified user.
	 *
	 * @param name
	 * @param user
	 * @return a project model, or null if it does not exist
	 */
	@Override
	public ProjectModel getProjectModel(String name, UserModel user) {
		for (ProjectModel project : getProjectModels(user, true)) {
			if (project.name.equalsIgnoreCase(name)) {
				return project;
			}
		}
		return null;
	}

	/**
	 * Returns a project model for the Gitblit/system user.
	 *
	 * @param name a project name
	 * @return a project model or null if the project does not exist
	 */
	@Override
	public ProjectModel getProjectModel(String name) {
		Map<String, ProjectModel> configs = getProjectConfigs();
		ProjectModel project = configs.get(name.toLowerCase());
		if (project == null) {
			project = new ProjectModel(name);
			if (ModelUtils.isPersonalRepository(name)) {
				UserModel user = getManager(IUserManager.class).getUserModel(ModelUtils.getUserNameFromRepoPath(name));
				if (user != null) {
					project.title = user.getDisplayName();
					project.description = "personal repositories";
				}
			}
		} else {
			// clone the object
			project = DeepCopier.copy(project);
		}
		if (StringUtils.isEmpty(name)) {
			// get root repositories
			for (String repository : getRepositoryList()) {
				if (repository.indexOf('/') == -1) {
					project.addRepository(repository);
				}
			}
		} else {
			// get repositories in subfolder
			String folder = name.toLowerCase() + "/";
			for (String repository : getRepositoryList()) {
				if (repository.toLowerCase().startsWith(folder)) {
					project.addRepository(repository);
				}
			}
		}
		if (project.repositories.size() == 0) {
			// no repositories == no project
			return null;
		}

		reloadProjectMarkdown(project);
		return project;
	}

	/**
	 * Returns the list of project models that are referenced by the supplied
	 * repository model	list.  This is an alternative method exists to ensure
	 * Gitblit does not call getRepositoryModels(UserModel) twice in a request.
	 *
	 * @param repositoryModels
	 * @param includeUsers
	 * @return a list of project models
	 */
	@Override
	public List<ProjectModel> getProjectModels(List<RepositoryModel> repositoryModels, boolean includeUsers) {
		Map<String, ProjectModel> projects = new LinkedHashMap<String, ProjectModel>();
		for (RepositoryModel repository : repositoryModels) {
			if (!includeUsers && repository.isPersonalRepository()) {
				// exclude personal repositories
				continue;
			}
			if (!projects.containsKey(repository.projectPath)) {
				ProjectModel project = getProjectModel(repository.projectPath);
				if (project == null) {
					logger.warn(MessageFormat.format("excluding project \"{0}\" from project list because it is empty!",
							repository.projectPath));
					continue;
				}
				projects.put(repository.projectPath, project);
				// clear the repo list in the project because that is the system
				// list, not the user-accessible list and start building the
				// user-accessible list
				project.repositories.clear();
				project.repositories.add(repository.name);
				project.lastChange = repository.lastChange;
			} else {
				// update the user-accessible list
				// this is used for repository count
				ProjectModel project = projects.get(repository.projectPath);
				project.repositories.add(repository.name);
				if (project.lastChange.before(repository.lastChange)) {
					project.lastChange = repository.lastChange;
				}
			}
		}
		return new ArrayList<ProjectModel>(projects.values());
	}

	/**
	 * Workaround JGit.  I need to access the raw config object directly in order
	 * to see if the config is dirty so that I can reload a repository model.
	 * If I use the stock JGit method to get the config it already reloads the
	 * config.  If the config changes are made within Gitblit this is fine as
	 * the returned config will still be flagged as dirty.  BUT... if the config
	 * is manipulated outside Gitblit then it fails to recognize this as dirty.
	 *
	 * @param r
	 * @return a config
	 */
	private StoredConfig getRepositoryConfig(Repository r) {
		try {
			Field f = r.getClass().getDeclaredField("repoConfig");
			f.setAccessible(true);
			StoredConfig config = (StoredConfig) f.get(r);
			return config;
		} catch (Exception e) {
			logger.error("Failed to retrieve \"repoConfig\" via reflection", e);
		}
		return r.getConfig();
	}

	/**
	 * Create a repository model from the configuration and repository data.
	 *
	 * @param repositoryName
	 * @return a repositoryModel or null if the repository does not exist
	 */
	private RepositoryModel loadRepositoryModel(String repositoryName) {
		Repository r = getRepository(repositoryName);
		if (r == null) {
			return null;
		}
		RepositoryModel model = new RepositoryModel();
		model.isBare = r.isBare();
		File basePath = getRepositoriesFolder();
		if (model.isBare) {
			model.name = com.gitblit.utils.FileUtils.getRelativePath(basePath, r.getDirectory());
		} else {
			model.name = com.gitblit.utils.FileUtils.getRelativePath(basePath, r.getDirectory().getParentFile());
		}
		if (StringUtils.isEmpty(model.name)) {
			// Repository is NOT located relative to the base folder because it
			// is symlinked.  Use the provided repository name.
			model.name = repositoryName;
		}
		model.projectPath = StringUtils.getFirstPathElement(repositoryName);

		StoredConfig config = r.getConfig();
		boolean hasOrigin = !StringUtils.isEmpty(config.getString("remote", "origin", "url"));

		if (config != null) {
			// Initialize description from description file
			if (getConfig(config,"description", null) == null) {
				File descFile = new File(r.getDirectory(), "description");
				if (descFile.exists()) {
					String desc = com.gitblit.utils.FileUtils.readContent(descFile, System.getProperty("line.separator"));
					if (!desc.toLowerCase().startsWith("unnamed repository")) {
						config.setString(Constants.CONFIG_GITBLIT, null, "description", desc);
					}
				}
			}
			model.description = getConfig(config, "description", "");
			model.originRepository = getConfig(config, "originRepository", null);
			model.addOwners(ArrayUtils.fromString(getConfig(config, "owner", "")));
			model.useIncrementalPushTags = getConfig(config, "useIncrementalPushTags", false);
			model.incrementalPushTagPrefix = getConfig(config, "incrementalPushTagPrefix", null);
			model.allowForks = getConfig(config, "allowForks", true);
			model.accessRestriction = AccessRestrictionType.fromName(getConfig(config,
					"accessRestriction", settings.getString(Keys.git.defaultAccessRestriction, "PUSH")));
			model.authorizationControl = AuthorizationControl.fromName(getConfig(config,
					"authorizationControl", settings.getString(Keys.git.defaultAuthorizationControl, null)));
			model.verifyCommitter = getConfig(config, "verifyCommitter", false);
			model.showRemoteBranches = getConfig(config, "showRemoteBranches", hasOrigin);
			model.isFrozen = getConfig(config, "isFrozen", false);
			model.skipSizeCalculation = getConfig(config, "skipSizeCalculation", false);
			model.skipSummaryMetrics = getConfig(config, "skipSummaryMetrics", false);
			model.commitMessageRenderer = CommitMessageRenderer.fromName(getConfig(config, "commitMessageRenderer",
					settings.getString(Keys.web.commitMessageRenderer, null)));
			model.federationStrategy = FederationStrategy.fromName(getConfig(config,
					"federationStrategy", null));
			model.federationSets = new ArrayList<String>(Arrays.asList(config.getStringList(
					Constants.CONFIG_GITBLIT, null, "federationSets")));
			model.isFederated = getConfig(config, "isFederated", false);
			model.gcThreshold = getConfig(config, "gcThreshold", settings.getString(Keys.git.defaultGarbageCollectionThreshold, "500KB"));
			model.gcPeriod = getConfig(config, "gcPeriod", settings.getInteger(Keys.git.defaultGarbageCollectionPeriod, 7));
			try {
				model.lastGC = new SimpleDateFormat(Constants.ISO8601).parse(getConfig(config, "lastGC", "1970-01-01'T'00:00:00Z"));
			} catch (Exception e) {
				model.lastGC = new Date(0);
			}
			model.maxActivityCommits = getConfig(config, "maxActivityCommits", settings.getInteger(Keys.web.maxActivityCommits, 0));
			model.origin = config.getString("remote", "origin", "url");
			if (model.origin != null) {
				model.origin = model.origin.replace('\\', '/');
				model.isMirror = config.getBoolean("remote", "origin", "mirror", false);
			}
			model.preReceiveScripts = new ArrayList<String>(Arrays.asList(config.getStringList(
					Constants.CONFIG_GITBLIT, null, "preReceiveScript")));
			model.postReceiveScripts = new ArrayList<String>(Arrays.asList(config.getStringList(
					Constants.CONFIG_GITBLIT, null, "postReceiveScript")));
			model.mailingLists = new ArrayList<String>(Arrays.asList(config.getStringList(
					Constants.CONFIG_GITBLIT, null, "mailingList")));
			model.indexedBranches = new ArrayList<String>(Arrays.asList(config.getStringList(
					Constants.CONFIG_GITBLIT, null, "indexBranch")));
			model.metricAuthorExclusions = new ArrayList<String>(Arrays.asList(config.getStringList(
					Constants.CONFIG_GITBLIT, null, "metricAuthorExclusions")));

			// Custom defined properties
			model.customFields = new LinkedHashMap<String, String>();
			for (String aProperty : config.getNames(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS)) {
				model.customFields.put(aProperty, config.getString(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS, aProperty));
			}
		}
		model.HEAD = JGitUtils.getHEADRef(r);
		model.availableRefs = JGitUtils.getAvailableHeadTargets(r);
		model.sparkleshareId = JGitUtils.getSparkleshareId(r);
		model.hasCommits = JGitUtils.hasCommits(r);
		updateLastChangeFields(r, model);
		r.close();

		if (StringUtils.isEmpty(model.originRepository) && model.origin != null && model.origin.startsWith("file://")) {
			// repository was cloned locally... perhaps as a fork
			try {
				File folder = new File(new URI(model.origin));
				String originRepo = com.gitblit.utils.FileUtils.getRelativePath(getRepositoriesFolder(), folder);
				if (!StringUtils.isEmpty(originRepo)) {
					// ensure origin still exists
					File repoFolder = new File(getRepositoriesFolder(), originRepo);
					if (repoFolder.exists()) {
						model.originRepository = originRepo.toLowerCase();

						// persist the fork origin
						updateConfiguration(r, model);
					}
				}
			} catch (URISyntaxException e) {
				logger.error("Failed to determine fork for " + model, e);
			}
		}
		return model;
	}

	/**
	 * Determines if this server has the requested repository.
	 *
	 * @param n
	 * @return true if the repository exists
	 */
	@Override
	public boolean hasRepository(String repositoryName) {
		return hasRepository(repositoryName, false);
	}

	/**
	 * Determines if this server has the requested repository.
	 *
	 * @param n
	 * @param caseInsensitive
	 * @return true if the repository exists
	 */
	@Override
	public boolean hasRepository(String repositoryName, boolean caseSensitiveCheck) {
		if (!caseSensitiveCheck && settings.getBoolean(Keys.git.cacheRepositoryList, true)) {
			// if we are caching use the cache to determine availability
			// otherwise we end up adding a phantom repository to the cache
			return repositoryListCache.containsKey(repositoryName.toLowerCase());
		}
		Repository r = getRepository(repositoryName, false);
		if (r == null) {
			return false;
		}
		r.close();
		return true;
	}

	/**
	 * Determines if the specified user has a fork of the specified origin
	 * repository.
	 *
	 * @param username
	 * @param origin
	 * @return true the if the user has a fork
	 */
	@Override
	public boolean hasFork(String username, String origin) {
		return getFork(username, origin) != null;
	}

	/**
	 * Gets the name of a user's fork of the specified origin
	 * repository.
	 *
	 * @param username
	 * @param origin
	 * @return the name of the user's fork, null otherwise
	 */
	@Override
	public String getFork(String username, String origin) {
		String userProject = ModelUtils.getPersonalPath(username);
		if (settings.getBoolean(Keys.git.cacheRepositoryList, true)) {
			String userPath = userProject + "/";

			// collect all origin nodes in fork network
			Set<String> roots = new HashSet<String>();
			roots.add(origin);
			RepositoryModel originModel = repositoryListCache.get(origin);
			while (originModel != null) {
				if (!ArrayUtils.isEmpty(originModel.forks)) {
					for (String fork : originModel.forks) {
						if (!fork.startsWith(userPath)) {
							roots.add(fork);
						}
					}
				}

				if (originModel.originRepository != null) {
					roots.add(originModel.originRepository);
					originModel = repositoryListCache.get(originModel.originRepository);
				} else {
					// break
					originModel = null;
				}
			}

			for (String repository : repositoryListCache.keySet()) {
				if (repository.startsWith(userPath)) {
					RepositoryModel model = repositoryListCache.get(repository);
					if (!StringUtils.isEmpty(model.originRepository)) {
						if (roots.contains(model.originRepository)) {
							// user has a fork in this graph
							return model.name;
						}
					}
				}
			}
		} else {
			// not caching
			ProjectModel project = getProjectModel(userProject);
			if (project == null) {
				return null;
			}
			for (String repository : project.repositories) {
				if (repository.startsWith(userProject)) {
					RepositoryModel model = getRepositoryModel(repository);
					if (model.originRepository.equalsIgnoreCase(origin)) {
						// user has a fork
						return model.name;
					}
				}
			}
		}
		// user does not have a fork
		return null;
	}

	/**
	 * Returns the fork network for a repository by traversing up the fork graph
	 * to discover the root and then down through all children of the root node.
	 *
	 * @param repository
	 * @return a ForkModel
	 */
	@Override
	public ForkModel getForkNetwork(String repository) {
		if (settings.getBoolean(Keys.git.cacheRepositoryList, true)) {
			// find the root, cached
			RepositoryModel model = repositoryListCache.get(repository.toLowerCase());
			while (model.originRepository != null) {
				model = repositoryListCache.get(model.originRepository);
			}
			ForkModel root = getForkModelFromCache(model.name);
			return root;
		} else {
			// find the root, non-cached
			RepositoryModel model = getRepositoryModel(repository.toLowerCase());
			while (model.originRepository != null) {
				model = getRepositoryModel(model.originRepository);
			}
			ForkModel root = getForkModel(model.name);
			return root;
		}
	}

	private ForkModel getForkModelFromCache(String repository) {
		RepositoryModel model = repositoryListCache.get(repository.toLowerCase());
		if (model == null) {
			return null;
		}
		ForkModel fork = new ForkModel(model);
		if (!ArrayUtils.isEmpty(model.forks)) {
			for (String aFork : model.forks) {
				ForkModel fm = getForkModelFromCache(aFork);
				if (fm != null) {
					fork.forks.add(fm);
				}
			}
		}
		return fork;
	}

	private ForkModel getForkModel(String repository) {
		RepositoryModel model = getRepositoryModel(repository.toLowerCase());
		if (model == null) {
			return null;
		}
		ForkModel fork = new ForkModel(model);
		if (!ArrayUtils.isEmpty(model.forks)) {
			for (String aFork : model.forks) {
				ForkModel fm = getForkModel(aFork);
				if (fm != null) {
					fork.forks.add(fm);
				}
			}
		}
		return fork;
	}

	/**
	 * Updates the last changed fields and optionally calculates the size of the
	 * repository.  Gitblit caches the repository sizes to reduce the performance
	 * penalty of recursive calculation. The cache is updated if the repository
	 * has been changed since the last calculation.
	 *
	 * @param model
	 * @return size in bytes of the repository
	 */
	@Override
	public long updateLastChangeFields(Repository r, RepositoryModel model) {
		LastChange lc = JGitUtils.getLastChange(r);
		model.lastChange = lc.when;
		model.lastChangeAuthor = lc.who;

		if (!settings.getBoolean(Keys.web.showRepositorySizes, true) || model.skipSizeCalculation) {
			model.size = null;
			return 0L;
		}
		if (!repositorySizeCache.hasCurrent(model.name, model.lastChange)) {
			File gitDir = r.getDirectory();
			long sz = com.gitblit.utils.FileUtils.folderSize(gitDir);
			repositorySizeCache.updateObject(model.name, model.lastChange, sz);
		}
		long size = repositorySizeCache.getObject(model.name);
		ByteFormat byteFormat = new ByteFormat();
		model.size = byteFormat.format(size);
		return size;
	}

	/**
	 * Ensure that a cached repository is completely closed and its resources
	 * are properly released.
	 *
	 * @param repositoryName
	 */
	private void closeRepository(String repositoryName) {
		Repository repository = getRepository(repositoryName);
		if (repository == null) {
			return;
		}
		RepositoryCache.close(repository);

		// assume 2 uses in case reflection fails
		int uses = 2;
		try {
			// The FileResolver caches repositories which is very useful
			// for performance until you want to delete a repository.
			// I have to use reflection to call close() the correct
			// number of times to ensure that the object and ref databases
			// are properly closed before I can delete the repository from
			// the filesystem.
			Field useCnt = Repository.class.getDeclaredField("useCnt");
			useCnt.setAccessible(true);
			uses = ((AtomicInteger) useCnt.get(repository)).get();
		} catch (Exception e) {
			logger.warn(MessageFormat
					.format("Failed to reflectively determine use count for repository {0}",
							repositoryName), e);
		}
		if (uses > 0) {
			logger.info(MessageFormat
					.format("{0}.useCnt={1}, calling close() {2} time(s) to close object and ref databases",
							repositoryName, uses, uses));
			for (int i = 0; i < uses; i++) {
				repository.close();
			}
		}

		// close any open index writer/searcher in the Lucene executor
		luceneExecutor.close(repositoryName);
	}

	/**
	 * Returns the metrics for the default branch of the specified repository.
	 * This method builds a metrics cache. The cache is updated if the
	 * repository is updated. A new copy of the metrics list is returned on each
	 * call so that modifications to the list are non-destructive.
	 *
	 * @param model
	 * @param repository
	 * @return a new array list of metrics
	 */
	@Override
	public List<Metric> getRepositoryDefaultMetrics(RepositoryModel model, Repository repository) {
		if (repositoryMetricsCache.hasCurrent(model.name, model.lastChange)) {
			return new ArrayList<Metric>(repositoryMetricsCache.getObject(model.name));
		}
		List<Metric> metrics = MetricUtils.getDateMetrics(repository, null, true, null, getManager(IRuntimeManager.class).getTimezone());
		repositoryMetricsCache.updateObject(model.name, model.lastChange, metrics);
		return new ArrayList<Metric>(metrics);
	}

	/**
	 * Returns the gitblit string value for the specified key. If key is not
	 * set, returns defaultValue.
	 *
	 * @param config
	 * @param field
	 * @param defaultValue
	 * @return field value or defaultValue
	 */
	private String getConfig(StoredConfig config, String field, String defaultValue) {
		String value = config.getString(Constants.CONFIG_GITBLIT, null, field);
		if (StringUtils.isEmpty(value)) {
			return defaultValue;
		}
		return value;
	}

	/**
	 * Returns the gitblit boolean value for the specified key. If key is not
	 * set, returns defaultValue.
	 *
	 * @param config
	 * @param field
	 * @param defaultValue
	 * @return field value or defaultValue
	 */
	private boolean getConfig(StoredConfig config, String field, boolean defaultValue) {
		return config.getBoolean(Constants.CONFIG_GITBLIT, field, defaultValue);
	}

	/**
	 * Returns the gitblit string value for the specified key. If key is not
	 * set, returns defaultValue.
	 *
	 * @param config
	 * @param field
	 * @param defaultValue
	 * @return field value or defaultValue
	 */
	private int getConfig(StoredConfig config, String field, int defaultValue) {
		String value = config.getString(Constants.CONFIG_GITBLIT, null, field);
		if (StringUtils.isEmpty(value)) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value);
		} catch (Exception e) {
		}
		return defaultValue;
	}

	/**
	 * Creates/updates the repository model keyed by reopsitoryName. Saves all
	 * repository settings in .git/config. This method allows for renaming
	 * repositories and will update user access permissions accordingly.
	 *
	 * All repositories created by this method are bare and automatically have
	 * .git appended to their names, which is the standard convention for bare
	 * repositories.
	 *
	 * @param repositoryName
	 * @param repository
	 * @param isCreate
	 * @throws GitBlitException
	 */
	@Override
	public void updateRepositoryModel(String repositoryName, RepositoryModel repository,
			boolean isCreate) throws GitBlitException {
		if (gcExecutor.isCollectingGarbage(repositoryName)) {
			throw new GitBlitException(MessageFormat.format("sorry, Gitblit is busy collecting garbage in {0}",
					repositoryName));
		}
		Repository r = null;
		String projectPath = StringUtils.getFirstPathElement(repository.name);
		if (!StringUtils.isEmpty(projectPath)) {
			if (projectPath.equalsIgnoreCase(settings.getString(Keys.web.repositoryRootGroupName, "main"))) {
				// strip leading group name
				repository.name = repository.name.substring(projectPath.length() + 1);
			}
		}
		if (isCreate) {
			// ensure created repository name ends with .git
			if (!repository.name.toLowerCase().endsWith(org.eclipse.jgit.lib.Constants.DOT_GIT_EXT)) {
				repository.name += org.eclipse.jgit.lib.Constants.DOT_GIT_EXT;
			}
			if (hasRepository(repository.name)) {
				throw new GitBlitException(MessageFormat.format(
						"Can not create repository ''{0}'' because it already exists.",
						repository.name));
			}
			// create repository
			logger.info("create repository " + repository.name);
			String shared = settings.getString(Keys.git.createRepositoriesShared, "FALSE");
			r = JGitUtils.createRepository(repositoriesFolder, repository.name, shared);
		} else {
			// rename repository
			if (!repositoryName.equalsIgnoreCase(repository.name)) {
				if (!repository.name.toLowerCase().endsWith(
						org.eclipse.jgit.lib.Constants.DOT_GIT_EXT)) {
					repository.name += org.eclipse.jgit.lib.Constants.DOT_GIT_EXT;
				}
				if (new File(repositoriesFolder, repository.name).exists()) {
					throw new GitBlitException(MessageFormat.format(
							"Failed to rename ''{0}'' because ''{1}'' already exists.",
							repositoryName, repository.name));
				}
				closeRepository(repositoryName);
				File folder = new File(repositoriesFolder, repositoryName);
				File destFolder = new File(repositoriesFolder, repository.name);
				if (destFolder.exists()) {
					throw new GitBlitException(
							MessageFormat
									.format("Can not rename repository ''{0}'' to ''{1}'' because ''{1}'' already exists.",
											repositoryName, repository.name));
				}
				File parentFile = destFolder.getParentFile();
				if (!parentFile.exists() && !parentFile.mkdirs()) {
					throw new GitBlitException(MessageFormat.format(
							"Failed to create folder ''{0}''", parentFile.getAbsolutePath()));
				}
				if (!folder.renameTo(destFolder)) {
					throw new GitBlitException(MessageFormat.format(
							"Failed to rename repository ''{0}'' to ''{1}''.", repositoryName,
							repository.name));
				}
				// rename the roles
				if (!getManager(IUserManager.class).renameRepositoryRole(repositoryName, repository.name)) {
					throw new GitBlitException(MessageFormat.format(
							"Failed to rename repository permissions ''{0}'' to ''{1}''.",
							repositoryName, repository.name));
				}

				// rename fork origins in their configs
				if (!ArrayUtils.isEmpty(repository.forks)) {
					for (String fork : repository.forks) {
						Repository rf = getRepository(fork);
						try {
							StoredConfig config = rf.getConfig();
							String origin = config.getString("remote", "origin", "url");
							origin = origin.replace(repositoryName, repository.name);
							config.setString("remote", "origin", "url", origin);
							config.setString(Constants.CONFIG_GITBLIT, null, "originRepository", repository.name);
							config.save();
						} catch (Exception e) {
							logger.error("Failed to update repository fork config for " + fork, e);
						}
						rf.close();
					}
				}

				// update this repository's origin's fork list
				if (!StringUtils.isEmpty(repository.originRepository)) {
					RepositoryModel origin = repositoryListCache.get(repository.originRepository);
					if (origin != null && !ArrayUtils.isEmpty(origin.forks)) {
						origin.forks.remove(repositoryName);
						origin.forks.add(repository.name);
					}
				}

				// clear the cache
				clearRepositoryMetadataCache(repositoryName);
				repository.resetDisplayName();
			}

			// load repository
			logger.info("edit repository " + repository.name);
			r = getRepository(repository.name);
		}

		// update settings
		if (r != null) {
			updateConfiguration(r, repository);
			// Update the description file
			File descFile = new File(r.getDirectory(), "description");
			if (repository.description != null)
			{
				com.gitblit.utils.FileUtils.writeContent(descFile, repository.description);
			}
			else if (descFile.exists() && !descFile.isDirectory()) {
				descFile.delete();
			}
			// only update symbolic head if it changes
			String currentRef = JGitUtils.getHEADRef(r);
			if (!StringUtils.isEmpty(repository.HEAD) && !repository.HEAD.equals(currentRef)) {
				logger.info(MessageFormat.format("Relinking {0} HEAD from {1} to {2}",
						repository.name, currentRef, repository.HEAD));
				if (JGitUtils.setHEADtoRef(r, repository.HEAD)) {
					// clear the cache
					clearRepositoryMetadataCache(repository.name);
				}
			}

			// Adjust permissions in case we updated the config files
			JGitUtils.adjustSharedPerm(new File(r.getDirectory().getAbsolutePath(), "config"),
					settings.getString(Keys.git.createRepositoriesShared, "FALSE"));
			JGitUtils.adjustSharedPerm(new File(r.getDirectory().getAbsolutePath(), "HEAD"),
					settings.getString(Keys.git.createRepositoriesShared, "FALSE"));

			// close the repository object
			r.close();
		}

		// update repository cache
		removeFromCachedRepositoryList(repositoryName);
		// model will actually be replaced on next load because config is stale
		addToCachedRepositoryList(repository);
	}

	/**
	 * Updates the Gitblit configuration for the specified repository.
	 *
	 * @param r
	 *            the Git repository
	 * @param repository
	 *            the Gitblit repository model
	 */
	@Override
	public void updateConfiguration(Repository r, RepositoryModel repository) {
		StoredConfig config = r.getConfig();
		config.setString(Constants.CONFIG_GITBLIT, null, "description", repository.description);
		config.setString(Constants.CONFIG_GITBLIT, null, "originRepository", repository.originRepository);
		config.setString(Constants.CONFIG_GITBLIT, null, "owner", ArrayUtils.toString(repository.owners));
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "useIncrementalPushTags", repository.useIncrementalPushTags);
		if (StringUtils.isEmpty(repository.incrementalPushTagPrefix) ||
				repository.incrementalPushTagPrefix.equals(settings.getString(Keys.git.defaultIncrementalPushTagPrefix, "r"))) {
			config.unset(Constants.CONFIG_GITBLIT, null, "incrementalPushTagPrefix");
		} else {
			config.setString(Constants.CONFIG_GITBLIT, null, "incrementalPushTagPrefix", repository.incrementalPushTagPrefix);
		}
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "allowForks", repository.allowForks);
		config.setString(Constants.CONFIG_GITBLIT, null, "accessRestriction", repository.accessRestriction.name());
		config.setString(Constants.CONFIG_GITBLIT, null, "authorizationControl", repository.authorizationControl.name());
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "verifyCommitter", repository.verifyCommitter);
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "showRemoteBranches", repository.showRemoteBranches);
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "isFrozen", repository.isFrozen);
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "skipSizeCalculation", repository.skipSizeCalculation);
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "skipSummaryMetrics", repository.skipSummaryMetrics);
		config.setString(Constants.CONFIG_GITBLIT, null, "federationStrategy",
				repository.federationStrategy.name());
		config.setBoolean(Constants.CONFIG_GITBLIT, null, "isFederated", repository.isFederated);
		config.setString(Constants.CONFIG_GITBLIT, null, "gcThreshold", repository.gcThreshold);
		if (repository.gcPeriod == settings.getInteger(Keys.git.defaultGarbageCollectionPeriod, 7)) {
			// use default from config
			config.unset(Constants.CONFIG_GITBLIT, null, "gcPeriod");
		} else {
			config.setInt(Constants.CONFIG_GITBLIT, null, "gcPeriod", repository.gcPeriod);
		}
		if (repository.lastGC != null) {
			config.setString(Constants.CONFIG_GITBLIT, null, "lastGC", new SimpleDateFormat(Constants.ISO8601).format(repository.lastGC));
		}
		if (repository.maxActivityCommits == settings.getInteger(Keys.web.maxActivityCommits, 0)) {
			// use default from config
			config.unset(Constants.CONFIG_GITBLIT, null, "maxActivityCommits");
		} else {
			config.setInt(Constants.CONFIG_GITBLIT, null, "maxActivityCommits", repository.maxActivityCommits);
		}

		CommitMessageRenderer defaultRenderer = CommitMessageRenderer.fromName(settings.getString(Keys.web.commitMessageRenderer, null));
		if (repository.commitMessageRenderer == null || repository.commitMessageRenderer == defaultRenderer) {
			// use default from config
			config.unset(Constants.CONFIG_GITBLIT, null, "commitMessageRenderer");
		} else {
			// repository overrides default
			config.setString(Constants.CONFIG_GITBLIT, null, "commitMessageRenderer",
					repository.commitMessageRenderer.name());
		}

		updateList(config, "federationSets", repository.federationSets);
		updateList(config, "preReceiveScript", repository.preReceiveScripts);
		updateList(config, "postReceiveScript", repository.postReceiveScripts);
		updateList(config, "mailingList", repository.mailingLists);
		updateList(config, "indexBranch", repository.indexedBranches);
		updateList(config, "metricAuthorExclusions", repository.metricAuthorExclusions);

		// User Defined Properties
		if (repository.customFields != null) {
			if (repository.customFields.size() == 0) {
				// clear section
				config.unsetSection(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS);
			} else {
				for (Entry<String, String> property : repository.customFields.entrySet()) {
					// set field
					String key = property.getKey();
					String value = property.getValue();
					config.setString(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS, key, value);
				}
			}
		}

		try {
			config.save();
		} catch (IOException e) {
			logger.error("Failed to save repository config!", e);
		}
	}

	private void updateList(StoredConfig config, String field, List<String> list) {
		// a null list is skipped, not cleared
		// this is for RPC administration where an older manager might be used
		if (list == null) {
			return;
		}
		if (ArrayUtils.isEmpty(list)) {
			config.unset(Constants.CONFIG_GITBLIT, null, field);
		} else {
			config.setStringList(Constants.CONFIG_GITBLIT, null, field, list);
		}
	}

	/**
	 * Deletes the repository from the file system and removes the repository
	 * permission from all repository users.
	 *
	 * @param model
	 * @return true if successful
	 */
	@Override
	public boolean deleteRepositoryModel(RepositoryModel model) {
		return deleteRepository(model.name);
	}

	/**
	 * Deletes the repository from the file system and removes the repository
	 * permission from all repository users.
	 *
	 * @param repositoryName
	 * @return true if successful
	 */
	@Override
	public boolean deleteRepository(String repositoryName) {
		try {
			closeRepository(repositoryName);
			// clear the repository cache
			clearRepositoryMetadataCache(repositoryName);

			RepositoryModel model = removeFromCachedRepositoryList(repositoryName);
			if (model != null && !ArrayUtils.isEmpty(model.forks)) {
				resetRepositoryListCache();
			}

			File folder = new File(repositoriesFolder, repositoryName);
			if (folder.exists() && folder.isDirectory()) {
				FileUtils.delete(folder, FileUtils.RECURSIVE | FileUtils.RETRY);
				if (getManager(IUserManager.class).deleteRepositoryRole(repositoryName)) {
					logger.info(MessageFormat.format("Repository \"{0}\" deleted", repositoryName));
					return true;
				}
			}
		} catch (Throwable t) {
			logger.error(MessageFormat.format("Failed to delete repository {0}", repositoryName), t);
		}
		return false;
	}

	/**
	 * Returns an html version of the commit message with any global or
	 * repository-specific regular expression substitution applied.
	 *
	 * This method uses the preferred renderer to transform the commit message.
	 *
	 * @param repository
	 * @param text
	 * @return html version of the commit message
	 */
	public String processCommitMessage(RepositoryModel repository, String text) {
		switch (repository.commitMessageRenderer) {
		case MARKDOWN:
			try {
				String prepared = processCommitMessageRegex(repository.name, text);
				return MarkdownUtils.transformMarkdown(prepared);
			} catch (Exception e) {
				logger.error("Failed to render commit message as markdown", e);
			}
			break;
		default:
			// noop
			break;
		}

		return processPlainCommitMessage(repository.name, text);
	}

	/**
	 * Returns an html version of the commit message with any global or
	 * repository-specific regular expression substitution applied.
	 *
	 * This method assumes the commit message is plain text.
	 *
	 * @param repositoryName
	 * @param text
	 * @return html version of the commit message
	 */
	public String processPlainCommitMessage(String repositoryName, String text) {
		String html = StringUtils.escapeForHtml(text, false);
		html = processCommitMessageRegex(repositoryName, html);
		return StringUtils.breakLinesForHtml(html);

	}

	/**
	 * Apply globally or per-repository specified regex substitutions to the
	 * commit message.
	 *
	 * @param repositoryName
	 * @param text
	 * @return the processed commit message
	 */
	protected String processCommitMessageRegex(String repositoryName, String text) {
		Map<String, String> map = new HashMap<String, String>();
		// global regex keys
		if (settings.getBoolean(Keys.regex.global, false)) {
			for (String key : settings.getAllKeys(Keys.regex.global)) {
				if (!key.equals(Keys.regex.global)) {
					String subKey = key.substring(key.lastIndexOf('.') + 1);
					map.put(subKey, settings.getString(key, ""));
				}
			}
		}

		// repository-specific regex keys
		List<String> keys = settings.getAllKeys(Keys.regex._ROOT + "."
				+ repositoryName.toLowerCase());
		for (String key : keys) {
			String subKey = key.substring(key.lastIndexOf('.') + 1);
			map.put(subKey, settings.getString(key, ""));
		}

		for (Entry<String, String> entry : map.entrySet()) {
			String definition = entry.getValue().trim();
			String[] chunks = definition.split("!!!");
			if (chunks.length == 2) {
				text = text.replaceAll(chunks[0], chunks[1]);
			} else {
				logger.warn(entry.getKey()
						+ " improperly formatted.  Use !!! to separate match from replacement: "
						+ definition);
			}
		}
		return text;
	}

	/**
	 * Returns Gitblit's scheduled executor service for scheduling tasks.
	 *
	 * @return scheduledExecutor
	 */
	public ScheduledExecutorService executor() {
		return scheduledExecutor;
	}

	@Override
	public boolean canFederate() {
		String passphrase = settings.getString(Keys.federation.passphrase, "");
		return !StringUtils.isEmpty(passphrase);
	}

	/**
	 * Configures this Gitblit instance to pull any registered federated gitblit
	 * instances.
	 */
	private void configureFederation() {
		boolean validPassphrase = true;
		String passphrase = settings.getString(Keys.federation.passphrase, "");
		if (StringUtils.isEmpty(passphrase)) {
			logger.warn("Federation passphrase is blank! This server can not be PULLED from.");
			validPassphrase = false;
		}
		if (validPassphrase) {
			// standard tokens
			for (FederationToken tokenType : FederationToken.values()) {
				logger.info(MessageFormat.format("Federation {0} token = {1}", tokenType.name(),
						getFederationToken(tokenType)));
			}

			// federation set tokens
			for (String set : settings.getStrings(Keys.federation.sets)) {
				logger.info(MessageFormat.format("Federation Set {0} token = {1}", set,
						getFederationToken(set)));
			}
		}

		// Schedule the federation executor
		List<FederationModel> registrations = getFederationRegistrations();
		if (registrations.size() > 0) {
			FederationPullExecutor executor = new FederationPullExecutor(registrations, true);
			scheduledExecutor.schedule(executor, 1, TimeUnit.MINUTES);
		}
	}

	/**
	 * Returns the list of federated gitblit instances that this instance will
	 * try to pull.
	 *
	 * @return list of registered gitblit instances
	 */
	@Override
	public List<FederationModel> getFederationRegistrations() {
		if (federationRegistrations.isEmpty()) {
			federationRegistrations.addAll(FederationUtils.getFederationRegistrations(settings));
		}
		return federationRegistrations;
	}

	/**
	 * Retrieve the specified federation registration.
	 *
	 * @param name
	 *            the name of the registration
	 * @return a federation registration
	 */
	@Override
	public FederationModel getFederationRegistration(String url, String name) {
		// check registrations
		for (FederationModel r : getFederationRegistrations()) {
			if (r.name.equals(name) && r.url.equals(url)) {
				return r;
			}
		}

		// check the results
		for (FederationModel r : getFederationResultRegistrations()) {
			if (r.name.equals(name) && r.url.equals(url)) {
				return r;
			}
		}
		return null;
	}

	/**
	 * Returns the list of federation sets.
	 *
	 * @return list of federation sets
	 */
	@Override
	public List<FederationSet> getFederationSets(String gitblitUrl) {
		List<FederationSet> list = new ArrayList<FederationSet>();
		// generate standard tokens
		for (FederationToken type : FederationToken.values()) {
			FederationSet fset = new FederationSet(type.toString(), type, getFederationToken(type));
			fset.repositories = getRepositories(gitblitUrl, fset.token);
			list.add(fset);
		}
		// generate tokens for federation sets
		for (String set : settings.getStrings(Keys.federation.sets)) {
			FederationSet fset = new FederationSet(set, FederationToken.REPOSITORIES,
					getFederationToken(set));
			fset.repositories = getRepositories(gitblitUrl, fset.token);
			list.add(fset);
		}
		return list;
	}

	/**
	 * Returns the list of possible federation tokens for this Gitblit instance.
	 *
	 * @return list of federation tokens
	 */
	@Override
	public List<String> getFederationTokens() {
		List<String> tokens = new ArrayList<String>();
		// generate standard tokens
		for (FederationToken type : FederationToken.values()) {
			tokens.add(getFederationToken(type));
		}
		// generate tokens for federation sets
		for (String set : settings.getStrings(Keys.federation.sets)) {
			tokens.add(getFederationToken(set));
		}
		return tokens;
	}

	/**
	 * Returns the specified federation token for this Gitblit instance.
	 *
	 * @param type
	 * @return a federation token
	 */
	@Override
	public String getFederationToken(FederationToken type) {
		return getFederationToken(type.name());
	}

	/**
	 * Returns the specified federation token for this Gitblit instance.
	 *
	 * @param value
	 * @return a federation token
	 */
	@Override
	public String getFederationToken(String value) {
		String passphrase = settings.getString(Keys.federation.passphrase, "");
		return StringUtils.getSHA1(passphrase + "-" + value);
	}

	/**
	 * Compares the provided token with this Gitblit instance's tokens and
	 * determines if the requested permission may be granted to the token.
	 *
	 * @param req
	 * @param token
	 * @return true if the request can be executed
	 */
	@Override
	public boolean validateFederationRequest(FederationRequest req, String token) {
		String all = getFederationToken(FederationToken.ALL);
		String unr = getFederationToken(FederationToken.USERS_AND_REPOSITORIES);
		String jur = getFederationToken(FederationToken.REPOSITORIES);
		switch (req) {
		case PULL_REPOSITORIES:
			return token.equals(all) || token.equals(unr) || token.equals(jur);
		case PULL_USERS:
		case PULL_TEAMS:
			return token.equals(all) || token.equals(unr);
		case PULL_SETTINGS:
		case PULL_SCRIPTS:
			return token.equals(all);
		default:
			break;
		}
		return false;
	}

	/**
	 * Acknowledge and cache the status of a remote Gitblit instance.
	 *
	 * @param identification
	 *            the identification of the pulling Gitblit instance
	 * @param registration
	 *            the registration from the pulling Gitblit instance
	 * @return true if acknowledged
	 */
	@Override
	public boolean acknowledgeFederationStatus(String identification, FederationModel registration) {
		// reset the url to the identification of the pulling Gitblit instance
		registration.url = identification;
		String id = identification;
		if (!StringUtils.isEmpty(registration.folder)) {
			id += "-" + registration.folder;
		}
		federationPullResults.put(id, registration);
		return true;
	}

	/**
	 * Returns the list of registration results.
	 *
	 * @return the list of registration results
	 */
	@Override
	public List<FederationModel> getFederationResultRegistrations() {
		return new ArrayList<FederationModel>(federationPullResults.values());
	}

	/**
	 * Submit a federation proposal. The proposal is cached locally and the
	 * Gitblit administrator(s) are notified via email.
	 *
	 * @param proposal
	 *            the proposal
	 * @param gitblitUrl
	 *            the url of your gitblit instance to send an email to
	 *            administrators
	 * @return true if the proposal was submitted
	 */
	@Override
	public boolean submitFederationProposal(FederationProposal proposal, String gitblitUrl) {
		// convert proposal to json
		String json = JsonUtils.toJsonString(proposal);

		try {
			// make the proposals folder
			File proposalsFolder = getProposalsFolder();
			proposalsFolder.mkdirs();

			// cache json to a file
			File file = new File(proposalsFolder, proposal.token + Constants.PROPOSAL_EXT);
			com.gitblit.utils.FileUtils.writeContent(file, json);
		} catch (Exception e) {
			logger.error(MessageFormat.format("Failed to cache proposal from {0}", proposal.url), e);
		}

		// send an email, if possible
		getManager(INotificationManager.class).sendMailToAdministrators("Federation proposal from " + proposal.url,
				"Please review the proposal @ " + gitblitUrl + "/proposal/" + proposal.token);
		return true;
	}

	/**
	 * Returns the list of pending federation proposals
	 *
	 * @return list of federation proposals
	 */
	@Override
	public List<FederationProposal> getPendingFederationProposals() {
		List<FederationProposal> list = new ArrayList<FederationProposal>();
		File folder = getProposalsFolder();
		if (folder.exists()) {
			File[] files = folder.listFiles(new FileFilter() {
				@Override
				public boolean accept(File file) {
					return file.isFile()
							&& file.getName().toLowerCase().endsWith(Constants.PROPOSAL_EXT);
				}
			});
			for (File file : files) {
				String json = com.gitblit.utils.FileUtils.readContent(file, null);
				FederationProposal proposal = JsonUtils.fromJsonString(json,
						FederationProposal.class);
				list.add(proposal);
			}
		}
		return list;
	}

	/**
	 * Get repositories for the specified token.
	 *
	 * @param gitblitUrl
	 *            the base url of this gitblit instance
	 * @param token
	 *            the federation token
	 * @return a map of <cloneurl, RepositoryModel>
	 */
	@Override
	public Map<String, RepositoryModel> getRepositories(String gitblitUrl, String token) {
		Map<String, String> federationSets = new HashMap<String, String>();
		for (String set : settings.getStrings(Keys.federation.sets)) {
			federationSets.put(getFederationToken(set), set);
		}

		// Determine the Gitblit clone url
		StringBuilder sb = new StringBuilder();
		sb.append(gitblitUrl);
		sb.append(Constants.GIT_PATH);
		sb.append("{0}");
		String cloneUrl = sb.toString();

		// Retrieve all available repositories
		UserModel user = getFederationUser();
		List<RepositoryModel> list = getRepositoryModels(user);

		// create the [cloneurl, repositoryModel] map
		Map<String, RepositoryModel> repositories = new HashMap<String, RepositoryModel>();
		for (RepositoryModel model : list) {
			// by default, setup the url for THIS repository
			String url = MessageFormat.format(cloneUrl, model.name);
			switch (model.federationStrategy) {
			case EXCLUDE:
				// skip this repository
				continue;
			case FEDERATE_ORIGIN:
				// federate the origin, if it is defined
				if (!StringUtils.isEmpty(model.origin)) {
					url = model.origin;
				}
				break;
			default:
				break;
			}

			if (federationSets.containsKey(token)) {
				// include repositories only for federation set
				String set = federationSets.get(token);
				if (model.federationSets.contains(set)) {
					repositories.put(url, model);
				}
			} else {
				// standard federation token for ALL
				repositories.put(url, model);
			}
		}
		return repositories;
	}

	/**
	 * Creates a proposal from the token.
	 *
	 * @param gitblitUrl
	 *            the url of this Gitblit instance
	 * @param token
	 * @return a potential proposal
	 */
	@Override
	public FederationProposal createFederationProposal(String gitblitUrl, String token) {
		FederationToken tokenType = FederationToken.REPOSITORIES;
		for (FederationToken type : FederationToken.values()) {
			if (token.equals(getFederationToken(type))) {
				tokenType = type;
				break;
			}
		}
		Map<String, RepositoryModel> repositories = getRepositories(gitblitUrl, token);
		FederationProposal proposal = new FederationProposal(gitblitUrl, tokenType, token,
				repositories);
		return proposal;
	}

	/**
	 * Returns the proposal identified by the supplied token.
	 *
	 * @param token
	 * @return the specified proposal or null
	 */
	@Override
	public FederationProposal getPendingFederationProposal(String token) {
		List<FederationProposal> list = getPendingFederationProposals();
		for (FederationProposal proposal : list) {
			if (proposal.token.equals(token)) {
				return proposal;
			}
		}
		return null;
	}

	/**
	 * Deletes a pending federation proposal.
	 *
	 * @param a
	 *            proposal
	 * @return true if the proposal was deleted
	 */
	@Override
	public boolean deletePendingFederationProposal(FederationProposal proposal) {
		File folder = getProposalsFolder();
		File file = new File(folder, proposal.token + Constants.PROPOSAL_EXT);
		return file.delete();
	}

	/**
	 * Returns the list of all Groovy push hook scripts. Script files must have
	 * .groovy extension
	 *
	 * @return list of available hook scripts
	 */
	@Override
	public List<String> getAllScripts() {
		File groovyFolder = getHooksFolder();
		File[] files = groovyFolder.listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				return pathname.isFile() && pathname.getName().endsWith(".groovy");
			}
		});
		List<String> scripts = new ArrayList<String>();
		if (files != null) {
			for (File file : files) {
				String script = file.getName().substring(0, file.getName().lastIndexOf('.'));
				scripts.add(script);
			}
		}
		return scripts;
	}

	/**
	 * Returns the list of pre-receive scripts the repository inherited from the
	 * global settings and team affiliations.
	 *
	 * @param repository
	 *            if null only the globally specified scripts are returned
	 * @return a list of scripts
	 */
	@Override
	public List<String> getPreReceiveScriptsInherited(RepositoryModel repository) {
		Set<String> scripts = new LinkedHashSet<String>();
		// Globals
		for (String script : settings.getStrings(Keys.groovy.preReceiveScripts)) {
			if (script.endsWith(".groovy")) {
				scripts.add(script.substring(0, script.lastIndexOf('.')));
			} else {
				scripts.add(script);
			}
		}

		// Team Scripts
		if (repository != null) {
			for (String teamname : getManager(IUserManager.class).getTeamNamesForRepositoryRole(repository.name)) {
				TeamModel team = getManager(IUserManager.class).getTeamModel(teamname);
				if (!ArrayUtils.isEmpty(team.preReceiveScripts)) {
					scripts.addAll(team.preReceiveScripts);
				}
			}
		}
		return new ArrayList<String>(scripts);
	}

	/**
	 * Returns the list of all available Groovy pre-receive push hook scripts
	 * that are not already inherited by the repository. Script files must have
	 * .groovy extension
	 *
	 * @param repository
	 *            optional parameter
	 * @return list of available hook scripts
	 */
	@Override
	public List<String> getPreReceiveScriptsUnused(RepositoryModel repository) {
		Set<String> inherited = new TreeSet<String>(getPreReceiveScriptsInherited(repository));

		// create list of available scripts by excluding inherited scripts
		List<String> scripts = new ArrayList<String>();
		for (String script : getAllScripts()) {
			if (!inherited.contains(script)) {
				scripts.add(script);
			}
		}
		return scripts;
	}

	/**
	 * Returns the list of post-receive scripts the repository inherited from
	 * the global settings and team affiliations.
	 *
	 * @param repository
	 *            if null only the globally specified scripts are returned
	 * @return a list of scripts
	 */
	@Override
	public List<String> getPostReceiveScriptsInherited(RepositoryModel repository) {
		Set<String> scripts = new LinkedHashSet<String>();
		// Global Scripts
		for (String script : settings.getStrings(Keys.groovy.postReceiveScripts)) {
			if (script.endsWith(".groovy")) {
				scripts.add(script.substring(0, script.lastIndexOf('.')));
			} else {
				scripts.add(script);
			}
		}
		// Team Scripts
		if (repository != null) {
			for (String teamname : getManager(IUserManager.class).getTeamNamesForRepositoryRole(repository.name)) {
				TeamModel team = getManager(IUserManager.class).getTeamModel(teamname);
				if (!ArrayUtils.isEmpty(team.postReceiveScripts)) {
					scripts.addAll(team.postReceiveScripts);
				}
			}
		}
		return new ArrayList<String>(scripts);
	}

	/**
	 * Returns the list of unused Groovy post-receive push hook scripts that are
	 * not already inherited by the repository. Script files must have .groovy
	 * extension
	 *
	 * @param repository
	 *            optional parameter
	 * @return list of available hook scripts
	 */
	@Override
	public List<String> getPostReceiveScriptsUnused(RepositoryModel repository) {
		Set<String> inherited = new TreeSet<String>(getPostReceiveScriptsInherited(repository));

		// create list of available scripts by excluding inherited scripts
		List<String> scripts = new ArrayList<String>();
		for (String script : getAllScripts()) {
			if (!inherited.contains(script)) {
				scripts.add(script);
			}
		}
		return scripts;
	}

	/**
	 * Search the specified repositories using the Lucene query.
	 *
	 * @param query
	 * @param page
	 * @param pageSize
	 * @param repositories
	 * @return
	 */
	@Override
	public List<SearchResult> search(String query, int page, int pageSize, List<String> repositories) {
		List<SearchResult> srs = luceneExecutor.search(query, page, pageSize, repositories);
		return srs;
	}

	/**
	 * Parse the properties file and aggregate all the comments by the setting
	 * key. A setting model tracks the current value, the default value, the
	 * description of the setting and and directives about the setting.
	 *
	 * @return Map<String, SettingModel>
	 */
	private ServerSettings loadSettingModels(ServerSettings settingsModel) {
		// this entire "supports" concept will go away with user service refactoring
		UserModel externalUser = new UserModel(Constants.EXTERNAL_ACCOUNT);
		externalUser.password = Constants.EXTERNAL_ACCOUNT;
		IUserManager userManager = getManager(IUserManager.class);
		settingsModel.supportsCredentialChanges = userManager.supportsCredentialChanges(externalUser);
		settingsModel.supportsDisplayNameChanges = userManager.supportsDisplayNameChanges(externalUser);
		settingsModel.supportsEmailAddressChanges = userManager.supportsEmailAddressChanges(externalUser);
		settingsModel.supportsTeamMembershipChanges = userManager.supportsTeamMembershipChanges(externalUser);
		try {
			// Read bundled Gitblit properties to extract setting descriptions.
			// This copy is pristine and only used for populating the setting
			// models map.
			InputStream is = getClass().getResourceAsStream("/reference.properties");
			BufferedReader propertiesReader = new BufferedReader(new InputStreamReader(is));
			StringBuilder description = new StringBuilder();
			SettingModel setting = new SettingModel();
			String line = null;
			while ((line = propertiesReader.readLine()) != null) {
				if (line.length() == 0) {
					description.setLength(0);
					setting = new SettingModel();
				} else {
					if (line.charAt(0) == '#') {
						if (line.length() > 1) {
							String text = line.substring(1).trim();
							if (SettingModel.CASE_SENSITIVE.equals(text)) {
								setting.caseSensitive = true;
							} else if (SettingModel.RESTART_REQUIRED.equals(text)) {
								setting.restartRequired = true;
							} else if (SettingModel.SPACE_DELIMITED.equals(text)) {
								setting.spaceDelimited = true;
							} else if (text.startsWith(SettingModel.SINCE)) {
								try {
									setting.since = text.split(" ")[1];
								} catch (Exception e) {
									setting.since = text;
								}
							} else {
								description.append(text);
								description.append('\n');
							}
						}
					} else {
						String[] kvp = line.split("=", 2);
						String key = kvp[0].trim();
						setting.name = key;
						setting.defaultValue = kvp[1].trim();
						setting.currentValue = setting.defaultValue;
						setting.description = description.toString().trim();
						settingsModel.add(setting);
						description.setLength(0);
						setting = new SettingModel();
					}
				}
			}
			propertiesReader.close();
		} catch (NullPointerException e) {
			logger.error("Failed to find resource copy of gitblit.properties");
		} catch (IOException e) {
			logger.error("Failed to load resource copy of gitblit.properties");
		}
		return settingsModel;
	}

	protected void configureLuceneIndexing() {
		scheduledExecutor.scheduleAtFixedRate(luceneExecutor, 1, 2,  TimeUnit.MINUTES);
		logger.info("Lucene executor is scheduled to process indexed branches every 2 minutes.");
	}

	protected void configureGarbageCollector() {
		// schedule gc engine
		if (gcExecutor.isReady()) {
			logger.info("GC executor is scheduled to scan repositories every 24 hours.");
			Calendar c = Calendar.getInstance();
			c.set(Calendar.HOUR_OF_DAY, settings.getInteger(Keys.git.garbageCollectionHour, 0));
			c.set(Calendar.MINUTE, 0);
			c.set(Calendar.SECOND, 0);
			c.set(Calendar.MILLISECOND, 0);
			Date cd = c.getTime();
			Date now = new Date();
			int delay = 0;
			if (cd.before(now)) {
				c.add(Calendar.DATE, 1);
				cd = c.getTime();
			}
			delay = (int) ((cd.getTime() - now.getTime())/TimeUtils.MIN);
			String when = delay + " mins";
			if (delay > 60) {
				when = MessageFormat.format("{0,number,0.0} hours", (delay)/60f);
			}
			logger.info(MessageFormat.format("Next scheculed GC scan is in {0}", when));
			scheduledExecutor.scheduleAtFixedRate(gcExecutor, delay, 60*24, TimeUnit.MINUTES);
		}
	}

	protected void configureMirrorExecutor() {
		if (mirrorExecutor.isReady()) {
			int mins = TimeUtils.convertFrequencyToMinutes(settings.getString(Keys.git.mirrorPeriod, "30 mins"));
			if (mins < 5) {
				mins = 5;
			}
			int delay = 1;
			scheduledExecutor.scheduleAtFixedRate(mirrorExecutor, delay, mins,  TimeUnit.MINUTES);
			logger.info("Mirror executor is scheduled to fetch updates every {} minutes.", mins);
			logger.info("Next scheduled mirror fetch is in {} minutes", delay);
		}
	}

	protected void configureJGit() {
		// Configure JGit
		WindowCacheConfig cfg = new WindowCacheConfig();

		cfg.setPackedGitWindowSize(settings.getFilesize(Keys.git.packedGitWindowSize, cfg.getPackedGitWindowSize()));
		cfg.setPackedGitLimit(settings.getFilesize(Keys.git.packedGitLimit, cfg.getPackedGitLimit()));
		cfg.setDeltaBaseCacheLimit(settings.getFilesize(Keys.git.deltaBaseCacheLimit, cfg.getDeltaBaseCacheLimit()));
		cfg.setPackedGitOpenFiles(settings.getFilesize(Keys.git.packedGitOpenFiles, cfg.getPackedGitOpenFiles()));
		cfg.setStreamFileThreshold(settings.getFilesize(Keys.git.streamFileThreshold, cfg.getStreamFileThreshold()));
		cfg.setPackedGitMMAP(settings.getBoolean(Keys.git.packedGitMmap, cfg.isPackedGitMMAP()));

		try {
			cfg.install();
			logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.packedGitWindowSize, cfg.getPackedGitWindowSize()));
			logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.packedGitLimit, cfg.getPackedGitLimit()));
			logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.deltaBaseCacheLimit, cfg.getDeltaBaseCacheLimit()));
			logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.packedGitOpenFiles, cfg.getPackedGitOpenFiles()));
			logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.streamFileThreshold, cfg.getStreamFileThreshold()));
			logger.debug(MessageFormat.format("{0} = {1}", Keys.git.packedGitMmap, cfg.isPackedGitMMAP()));
		} catch (IllegalArgumentException e) {
			logger.error("Failed to configure JGit parameters!", e);
		}
	}

	protected void configureFanout() {
		// startup Fanout PubSub service
		if (settings.getInteger(Keys.fanout.port, 0) > 0) {
			String bindInterface = settings.getString(Keys.fanout.bindInterface, null);
			int port = settings.getInteger(Keys.fanout.port, FanoutService.DEFAULT_PORT);
			boolean useNio = settings.getBoolean(Keys.fanout.useNio, true);
			int limit = settings.getInteger(Keys.fanout.connectionLimit, 0);

			if (useNio) {
				if (StringUtils.isEmpty(bindInterface)) {
					fanoutService = new FanoutNioService(port);
				} else {
					fanoutService = new FanoutNioService(bindInterface, port);
				}
			} else {
				if (StringUtils.isEmpty(bindInterface)) {
					fanoutService = new FanoutSocketService(port);
				} else {
					fanoutService = new FanoutSocketService(bindInterface, port);
				}
			}

			fanoutService.setConcurrentConnectionLimit(limit);
			fanoutService.setAllowAllChannelAnnouncements(false);
			fanoutService.start();
		}
	}

	protected void configureGitDaemon() {
		int port = settings.getInteger(Keys.git.daemonPort, 0);
		String bindInterface = settings.getString(Keys.git.daemonBindInterface, "localhost");
		if (port > 0) {
			try {
				// HACK temporary pending manager separation and injection
				Gitblit gitblit = new Gitblit(
						getManager(IRuntimeManager.class),
						getManager(INotificationManager.class),
						getManager(IUserManager.class),
						this,
						this,
						this,
						this,
						this);
				gitDaemon = new GitDaemon(gitblit);
				gitDaemon.start();
			} catch (IOException e) {
				gitDaemon = null;
				logger.error(MessageFormat.format("Failed to start Git daemon on {0}:{1,number,0}", bindInterface, port), e);
			}
		}
	}

	protected void configureCommitCache() {
		int daysToCache = settings.getInteger(Keys.web.activityCacheDays, 14);
		if (daysToCache <= 0) {
			logger.info("commit cache disabled");
		} else {
			long start = System.nanoTime();
			long repoCount = 0;
			long commitCount = 0;
			logger.info(MessageFormat.format("preparing {0} day commit cache. please wait...", daysToCache));
			CommitCache.instance().setCacheDays(daysToCache);
			Date cutoff = CommitCache.instance().getCutoffDate();
			for (String repositoryName : getRepositoryList()) {
				RepositoryModel model = getRepositoryModel(repositoryName);
				if (model != null && model.hasCommits && model.lastChange.after(cutoff)) {
					repoCount++;
					Repository repository = getRepository(repositoryName);
					for (RefModel ref : JGitUtils.getLocalBranches(repository, true, -1)) {
						if (!ref.getDate().after(cutoff)) {
							// branch not recently updated
							continue;
						}
						List<?> commits = CommitCache.instance().getCommits(repositoryName, repository, ref.getName());
						if (commits.size() > 0) {
							logger.info(MessageFormat.format("  cached {0} commits for {1}:{2}",
									commits.size(), repositoryName, ref.getName()));
							commitCount += commits.size();
						}
					}
					repository.close();
				}
			}
			logger.info(MessageFormat.format("built {0} day commit cache of {1} commits across {2} repositories in {3} msecs",
					daysToCache, commitCount, repoCount, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
		}
	}

	protected final Logger getLogger() {
		return logger;
	}

	protected final ScheduledExecutorService getScheduledExecutor() {
		return scheduledExecutor;
	}

	protected final LuceneExecutor getLuceneExecutor() {
		return luceneExecutor;
	}

	/**
	 * Configure Gitblit from the web.xml, if no configuration has already been
	 * specified.
	 *
	 * @see ServletContextListener.contextInitialize(ServletContextEvent)
	 */
	@Override
	protected void beforeServletInjection(ServletContext context) {
		ObjectGraph injector = getInjector(context);

		// create the runtime settings object
		IStoredSettings runtimeSettings = injector.get(IStoredSettings.class);
		this.settings = runtimeSettings; // XXX remove me eventually
		final File baseFolder;

		if (goSettings != null) {
			// Gitblit GO
			logger.debug("configuring Gitblit GO");
			baseFolder = configureGO(context, goSettings, goBaseFolder, runtimeSettings);
		} else {
			// servlet container
			WebXmlSettings webxmlSettings = new WebXmlSettings(context);
			String contextRealPath = context.getRealPath("/");
			File contextFolder = (contextRealPath != null) ? new File(contextRealPath) : null;

			if (!StringUtils.isEmpty(System.getenv("OPENSHIFT_DATA_DIR"))) {
				// RedHat OpenShift
				logger.debug("configuring Gitblit Express");
				baseFolder = configureExpress(context, webxmlSettings, contextFolder, runtimeSettings);
			} else {
				// standard WAR
				logger.debug("configuring Gitblit WAR");
				baseFolder = configureWAR(context, webxmlSettings, contextFolder, runtimeSettings);
			}

			// Test for Tomcat forward-slash/%2F issue and auto-adjust settings
			ContainerUtils.CVE_2007_0450.test(runtimeSettings);
		}

		// Runtime manager is a container for settings and other parameters
		IRuntimeManager runtime = startManager(injector, IRuntimeManager.class);
		runtime.setBaseFolder(baseFolder);
		runtime.getStatus().isGO = goSettings != null;
		runtime.getStatus().servletContainer = context.getServerInfo();

		startManager(injector, INotificationManager.class);
		startManager(injector, IUserManager.class);

		repositoriesFolder = getRepositoriesFolder();

		logger.info("Gitblit base folder     = " + baseFolder.getAbsolutePath());
		logger.info("Git repositories folder = " + repositoriesFolder.getAbsolutePath());

		// prepare service executors
		luceneExecutor = new LuceneExecutor(runtimeSettings, getManager(IRepositoryManager.class));
		gcExecutor = new GCExecutor(runtimeSettings, getManager(IRepositoryManager.class));
		mirrorExecutor = new MirrorExecutor(runtimeSettings, getManager(IRepositoryManager.class));

		// initialize utilities
		String prefix = runtimeSettings.getString(Keys.git.userRepositoryPrefix, "~");
		ModelUtils.setUserRepoPrefix(prefix);

		// calculate repository list settings checksum for future config changes
		repositoryListSettingsChecksum.set(getRepositoryListSettingsChecksum());

		// build initial repository list
		if (runtimeSettings.getBoolean(Keys.git.cacheRepositoryList,  true)) {
			logger.info("Identifying available repositories...");
			getRepositoryList();
		}

		loadSettingModels(runtime.getSettingsModel());

		// load and cache the project metadata
		projectConfigs = new FileBasedConfig(runtime.getFileOrFolder(Keys.web.projectsFile, "${baseFolder}/projects.conf"), FS.detect());
		getProjectConfigs();

		configureLuceneIndexing();
		configureGarbageCollector();
		configureMirrorExecutor();
		if (true/*startFederation*/) {
			configureFederation();
		}
		configureJGit();
		configureFanout();
		configureGitDaemon();
		configureCommitCache();
	}

	/**
	 * Configures Gitblit GO
	 *
	 * @param context
	 * @param settings
	 * @param baseFolder
	 * @param runtimeSettings
	 * @return the base folder
	 */
	protected File configureGO(
			ServletContext context,
			IStoredSettings goSettings,
			File goBaseFolder,
			IStoredSettings runtimeSettings) {

		// merge the stored settings into the runtime settings
		//
		// if runtimeSettings is also a FileSettings w/o a specified target file,
		// the target file for runtimeSettings is set to "localSettings".
		runtimeSettings.merge(goSettings);
		File base = goBaseFolder;
		return base;
	}


	/**
	 * Configures a standard WAR instance of Gitblit.
	 *
	 * @param context
	 * @param webxmlSettings
	 * @param contextFolder
	 * @param runtimeSettings
	 * @return the base folder
	 */
	protected File configureWAR(
			ServletContext context,
			WebXmlSettings webxmlSettings,
			File contextFolder,
			IStoredSettings runtimeSettings) {

		// Gitblit is running in a standard servlet container
		logger.info("WAR contextFolder is " + ((contextFolder != null) ? contextFolder.getAbsolutePath() : "<empty>"));

		String path = webxmlSettings.getString(Constants.baseFolder, Constants.contextFolder$ + "/WEB-INF/data");

		if (path.contains(Constants.contextFolder$) && contextFolder == null) {
			// warn about null contextFolder (issue-199)
			logger.error("");
			logger.error(MessageFormat.format("\"{0}\" depends on \"{1}\" but \"{2}\" is returning NULL for \"{1}\"!",
					Constants.baseFolder, Constants.contextFolder$, context.getServerInfo()));
			logger.error(MessageFormat.format("Please specify a non-parameterized path for <context-param> {0} in web.xml!!", Constants.baseFolder));
			logger.error(MessageFormat.format("OR configure your servlet container to specify a \"{0}\" parameter in the context configuration!!", Constants.baseFolder));
			logger.error("");
		}

		try {
			// try to lookup JNDI env-entry for the baseFolder
			InitialContext ic = new InitialContext();
			Context env = (Context) ic.lookup("java:comp/env");
			String val = (String) env.lookup("baseFolder");
			if (!StringUtils.isEmpty(val)) {
				path = val;
			}
		} catch (NamingException n) {
			logger.error("Failed to get JNDI env-entry: " + n.getExplanation());
		}

		File base = com.gitblit.utils.FileUtils.resolveParameter(Constants.contextFolder$, contextFolder, path);
		base.mkdirs();

		// try to extract the data folder resource to the baseFolder
		File localSettings = new File(base, "gitblit.properties");
		if (!localSettings.exists()) {
			extractResources(context, "/WEB-INF/data/", base);
		}

		// delegate all config to baseFolder/gitblit.properties file
		FileSettings fileSettings = new FileSettings(localSettings.getAbsolutePath());

		// merge the stored settings into the runtime settings
		//
		// if runtimeSettings is also a FileSettings w/o a specified target file,
		// the target file for runtimeSettings is set to "localSettings".
		runtimeSettings.merge(fileSettings);

		return base;
	}

	/**
	 * Configures an OpenShift instance of Gitblit.
	 *
	 * @param context
	 * @param webxmlSettings
	 * @param contextFolder
	 * @param runtimeSettings
	 * @return the base folder
	 */
	private File configureExpress(
			ServletContext context,
			WebXmlSettings webxmlSettings,
			File contextFolder,
			IStoredSettings runtimeSettings) {

		// Gitblit is running in OpenShift/JBoss
		String openShift = System.getenv("OPENSHIFT_DATA_DIR");
		File base = new File(openShift);
		logger.info("EXPRESS contextFolder is " + contextFolder.getAbsolutePath());

		// Copy the included scripts to the configured groovy folder
		String path = webxmlSettings.getString(Keys.groovy.scriptsFolder, "groovy");
		File localScripts = com.gitblit.utils.FileUtils.resolveParameter(Constants.baseFolder$, base, path);
		if (!localScripts.exists()) {
			File warScripts = new File(contextFolder, "/WEB-INF/data/groovy");
			if (!warScripts.equals(localScripts)) {
				try {
					com.gitblit.utils.FileUtils.copy(localScripts, warScripts.listFiles());
				} catch (IOException e) {
					logger.error(MessageFormat.format(
							"Failed to copy included Groovy scripts from {0} to {1}",
							warScripts, localScripts));
				}
			}
		}

		// merge the WebXmlSettings into the runtime settings (for backwards-compatibilty)
		runtimeSettings.merge(webxmlSettings);

		// settings are to be stored in openshift/gitblit.properties
		File localSettings = new File(base, "gitblit.properties");
		FileSettings fileSettings = new FileSettings(localSettings.getAbsolutePath());

		// merge the stored settings into the runtime settings
		//
		// if runtimeSettings is also a FileSettings w/o a specified target file,
		// the target file for runtimeSettings is set to "localSettings".
		runtimeSettings.merge(fileSettings);

		return base;
	}

	protected void extractResources(ServletContext context, String path, File toDir) {
		for (String resource : context.getResourcePaths(path)) {
			// extract the resource to the directory if it does not exist
			File f = new File(toDir, resource.substring(path.length()));
			if (!f.exists()) {
				InputStream is = null;
				OutputStream os = null;
				try {
					if (resource.charAt(resource.length() - 1) == '/') {
						// directory
						f.mkdirs();
						extractResources(context, resource, f);
					} else {
						// file
						f.getParentFile().mkdirs();
						is = context.getResourceAsStream(resource);
						os = new FileOutputStream(f);
						byte [] buffer = new byte[4096];
						int len = 0;
						while ((len = is.read(buffer)) > -1) {
							os.write(buffer, 0, len);
						}
					}
				} catch (FileNotFoundException e) {
					logger.error("Failed to find resource \"" + resource + "\"", e);
				} catch (IOException e) {
					logger.error("Failed to copy resource \"" + resource + "\" to " + f, e);
				} finally {
					if (is != null) {
						try {
							is.close();
						} catch (IOException e) {
							// ignore
						}
					}
					if (os != null) {
						try {
							os.close();
						} catch (IOException e) {
							// ignore
						}
					}
				}
			}
		}
	}

	/**
	 * Gitblit is being shutdown either because the servlet container is
	 * shutting down or because the servlet container is re-deploying Gitblit.
	 */
	@Override
	protected void destroyContext(ServletContext context) {
		logger.info("Gitblit context destroyed by servlet container.");
		for (IManager manager : managers) {
			logger.debug("stopping {}", manager.getClass().getSimpleName());
			manager.stop();
		}

		scheduledExecutor.shutdownNow();
		luceneExecutor.close();
		gcExecutor.close();
		mirrorExecutor.close();
		if (fanoutService != null) {
			fanoutService.stop();
		}
		if (gitDaemon != null) {
			gitDaemon.stop();
		}
	}

	/**
	 *
	 * @return true if we are running the gc executor
	 */
	@Override
	public boolean isCollectingGarbage() {
		return gcExecutor.isRunning();
	}

	/**
	 * Returns true if Gitblit is actively collecting garbage in this repository.
	 *
	 * @param repositoryName
	 * @return true if actively collecting garbage
	 */
	@Override
	public boolean isCollectingGarbage(String repositoryName) {
		return gcExecutor.isCollectingGarbage(repositoryName);
	}

	/**
	 * Creates a personal fork of the specified repository. The clone is view
	 * restricted by default and the owner of the source repository is given
	 * access to the clone.
	 *
	 * @param repository
	 * @param user
	 * @return the repository model of the fork, if successful
	 * @throws GitBlitException
	 */
	@Override
	public RepositoryModel fork(RepositoryModel repository, UserModel user) throws GitBlitException {
		String cloneName = MessageFormat.format("{0}/{1}.git", user.getPersonalPath(), StringUtils.stripDotGit(StringUtils.getLastPathElement(repository.name)));
		String fromUrl = MessageFormat.format("file://{0}/{1}", repositoriesFolder.getAbsolutePath(), repository.name);

		// clone the repository
		try {
			JGitUtils.cloneRepository(repositoriesFolder, cloneName, fromUrl, true, null);
		} catch (Exception e) {
			throw new GitBlitException(e);
		}

		// create a Gitblit repository model for the clone
		RepositoryModel cloneModel = repository.cloneAs(cloneName);
		// owner has REWIND/RW+ permissions
		cloneModel.addOwner(user.username);
		updateRepositoryModel(cloneName, cloneModel, false);

		// add the owner of the source repository to the clone's access list
		if (!ArrayUtils.isEmpty(repository.owners)) {
			for (String owner : repository.owners) {
				UserModel originOwner = getManager(IUserManager.class).getUserModel(owner);
				if (originOwner != null) {
					originOwner.setRepositoryPermission(cloneName, AccessPermission.CLONE);
					updateUserModel(originOwner.username, originOwner, false);
				}
			}
		}

		// grant origin's user list clone permission to fork
		List<String> users = getRepositoryUsers(repository);
		List<UserModel> cloneUsers = new ArrayList<UserModel>();
		for (String name : users) {
			if (!name.equalsIgnoreCase(user.username)) {
				UserModel cloneUser = getManager(IUserManager.class).getUserModel(name);
				if (cloneUser.canClone(repository)) {
					// origin user can clone origin, grant clone access to fork
					cloneUser.setRepositoryPermission(cloneName, AccessPermission.CLONE);
				}
				cloneUsers.add(cloneUser);
			}
		}
		getManager(IUserManager.class).updateUserModels(cloneUsers);

		// grant origin's team list clone permission to fork
		List<String> teams = getRepositoryTeams(repository);
		List<TeamModel> cloneTeams = new ArrayList<TeamModel>();
		for (String name : teams) {
			TeamModel cloneTeam = getManager(IUserManager.class).getTeamModel(name);
			if (cloneTeam.canClone(repository)) {
				// origin team can clone origin, grant clone access to fork
				cloneTeam.setRepositoryPermission(cloneName, AccessPermission.CLONE);
			}
			cloneTeams.add(cloneTeam);
		}
		getManager(IUserManager.class).updateTeamModels(cloneTeams);

		// add this clone to the cached model
		addToCachedRepositoryList(cloneModel);
		return cloneModel;
	}

	@Override
	public void logout(HttpServletResponse response, UserModel user) {
		setCookie(response,  null);
		getManager(IUserManager.class).logout(user);
	}

	@Override
	protected Object [] getModules() {
		return new Object [] { new DaggerModule(this) };
	}

	protected <X extends IManager> X startManager(ObjectGraph injector, Class<X> clazz) {
		logger.debug("injecting and starting {}", clazz.getSimpleName());
		X x = injector.get(clazz);
		x.setup();
		managers.add(x);
		return x;
	}

	/**
	 * Instantiate and inject all filters and servlets into the container using
	 * the servlet 3 specification.
	 */
	@Override
	protected void injectServlets(ServletContext context) {
		// access restricted servlets
		serve(context, Constants.GIT_PATH, GitServlet.class, GitFilter.class);
		serve(context, Constants.PAGES, PagesServlet.class, PagesFilter.class);
		serve(context, Constants.RPC_PATH, RpcServlet.class, RpcFilter.class);
		serve(context, Constants.ZIP_PATH, DownloadZipServlet.class, DownloadZipFilter.class);
		serve(context, Constants.SYNDICATION_PATH, SyndicationServlet.class, SyndicationFilter.class);

		// servlets
		serve(context, Constants.FEDERATION_PATH, FederationServlet.class);
		serve(context, Constants.SPARKLESHARE_INVITE_PATH, SparkleShareInviteServlet.class);
		serve(context, Constants.BRANCH_GRAPH_PATH, BranchGraphServlet.class);
		file(context, "/robots.txt", RobotsTxtServlet.class);
		file(context, "/logo.png", LogoServlet.class);

		// optional force basic authentication
		filter(context, "/*", EnforceAuthenticationFilter.class, null);

		// Wicket
		String toIgnore = StringUtils.flattenStrings(getRegisteredPaths(), ",");
		Map<String, String> params = new HashMap<String, String>();
		params.put(GitblitWicketFilter.FILTER_MAPPING_PARAM, "/*");
		params.put(GitblitWicketFilter.IGNORE_PATHS_PARAM, toIgnore);
		filter(context, "/*", GitblitWicketFilter.class, params);
	}
}
