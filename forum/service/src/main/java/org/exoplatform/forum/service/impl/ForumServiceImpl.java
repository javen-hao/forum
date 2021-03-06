/***************************************************************************
 * Copyright (C) 2003-2007 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 ***************************************************************************/
package org.exoplatform.forum.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.jcr.NodeIterator;

import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.forum.common.conf.RoleRulesPlugin;
import org.exoplatform.forum.common.lifecycle.LifeCycleCompletionService;
import org.exoplatform.forum.service.Category;
import org.exoplatform.forum.service.DataStorage;
import org.exoplatform.forum.service.Forum;
import org.exoplatform.forum.service.ForumAdministration;
import org.exoplatform.forum.service.ForumAttachment;
import org.exoplatform.forum.service.ForumEventLifeCycle;
import org.exoplatform.forum.service.ForumEventListener;
import org.exoplatform.forum.service.ForumEventQuery;
import org.exoplatform.forum.service.ForumLinkData;
import org.exoplatform.forum.service.ForumPrivateMessage;
import org.exoplatform.forum.service.ForumSearchResult;
import org.exoplatform.forum.service.ForumService;
import org.exoplatform.forum.service.ForumStatistic;
import org.exoplatform.forum.service.ForumStatisticsService;
import org.exoplatform.forum.service.ForumSubscription;
import org.exoplatform.forum.service.InitializeForumPlugin;
import org.exoplatform.forum.service.JCRPageList;
import org.exoplatform.forum.service.LazyPageList;
import org.exoplatform.forum.service.MessageBuilder;
import org.exoplatform.forum.service.Post;
import org.exoplatform.forum.service.PruneSetting;
import org.exoplatform.forum.service.SendMessageInfo;
import org.exoplatform.forum.service.Tag;
import org.exoplatform.forum.service.Topic;
import org.exoplatform.forum.service.UserLoginLogEntry;
import org.exoplatform.forum.service.UserProfile;
import org.exoplatform.forum.service.Utils;
import org.exoplatform.forum.service.Watch;
import org.exoplatform.forum.service.filter.model.CategoryFilter;
import org.exoplatform.forum.service.filter.model.ForumFilter;
import org.exoplatform.forum.service.impl.model.PostFilter;
import org.exoplatform.forum.service.impl.model.PostListAccess;
import org.exoplatform.forum.service.impl.model.TopicFilter;
import org.exoplatform.forum.service.impl.model.TopicListAccess;
import org.exoplatform.forum.service.impl.model.UserProfileFilter;
import org.exoplatform.forum.service.impl.model.UserProfileListAccess;
import org.exoplatform.management.annotations.ManagedBy;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.services.user.UserStateService;
import org.picocontainer.Startable;
import org.quartz.JobDetail;

@ManagedBy(ForumServiceManaged.class)
public class ForumServiceImpl implements ForumService, Startable {

  private static final Log         log             = ExoLogger.getLogger(ForumServiceImpl.class);

  private DataStorage                storage;

  private ForumServiceManaged        managementView; // will be automatically set at @ManagedBy processing

  private final Map<String, LinkedList<UserLoginLogEntry>> queueMap_  = new HashMap<String, LinkedList<UserLoginLogEntry>>();

  private ForumStatisticsService     forumStatisticsService;

  private LifeCycleCompletionService completionService;

  private JobSchedulerService        jobSchedulerService;

  private UserStateService           userStateService;

  protected List<ForumEventListener> listeners_      = new ArrayList<ForumEventListener>(3);
  
  public ForumServiceImpl(InitParams params, ExoContainerContext context, DataStorage dataStorage, ForumStatisticsService staticsService,
                          JobSchedulerService jobService, UserStateService userStateService, LifeCycleCompletionService completionService) {
    this.storage = dataStorage;
    this.forumStatisticsService = staticsService;
    this.jobSchedulerService = jobService;
    this.userStateService = userStateService;
    this.completionService = completionService;
  }

  /**
   * {@inheritDoc}
   */
  public void addPlugin(ComponentPlugin plugin) throws Exception {
    storage.addPlugin(plugin);
  }

  /**
   * {@inheritDoc}
   */
  public void addRolePlugin(ComponentPlugin plugin) throws Exception {
    storage.addRolePlugin(plugin);
  }

  /**
   * {@inheritDoc}
   */
  public void addInitialDefaultDataPlugin(ComponentPlugin plugin) throws Exception {
    storage.addInitialDefaultDataPlugin(plugin);
  }

  public void addInitialDataPlugin(ComponentPlugin plugin) throws Exception {
    storage.addInitialDataPlugin(plugin);
  }

  public void start() {

    try {
      log.info("initializing category listeners...");
      storage.initCategoryListener();
    } catch (Exception e) {
      log.error("Error while updating category listeners " + e.getMessage());
    }

    try {
      log.info("initializing default data...");
      storage.initDefaultData();

    } catch (Exception e) {
      log.error("Error while initializing default data: " + e.getMessage());
    }

    try {
      log.info("initializing data...");
      storage.initDataPlugin();
    } catch (Exception e) {
      log.error("Error while initializing data plugin: " + e.getMessage());
    }

    try {
      log.info("Calculating active users...");
      storage.evaluateActiveUsers("");
    } catch (Exception e) {
      log.error("Error while calculating active users: " + e.getMessage());
    }

    // initialize auto prune schedules
    try {
      log.info("initializing prune schedulers...");
      storage.initAutoPruneSchedules();
    } catch (Exception e) {
      log.error("Error while initializing Prune schedulers: " + e.getMessage());
    }

    // init deleted user listeners
    try {
      log.info("initializing deleted user listener...");
      storage.addDeletedUserCalculateListener();
    } catch (Exception e) {
      log.error("Error while initializing Prune schedulers: " + e.getMessage());
    }

    // management views
    try {
      log.info("initializing management view...");
      managePlugins();
      manageStorage();
      manageJobs();
    } catch (Exception e) {
      log.error("Error while initializing Management view: " + e.getMessage());
    }
  }

  private void manageStorage() {
    managementView.registerStorageManager(storage);
  }

  private void manageJobs() {
    try {
      List<JobDetail> jobs = jobSchedulerService.getAllJobs();
      for (JobDetail jobDetail : jobs) {
        if (JobManager.forumJobs.contains(jobDetail.getJobClass().getName())) {
          managementView.registerJobManager(new JobManager(jobDetail));
        }
      }
    } catch (Exception e) {
      log.error("failed to register jobs manager", e);
    }
  }

  private void managePlugins() {
    List<RoleRulesPlugin> plugins = storage.getRulesPlugins();
    for (RoleRulesPlugin plugin2 : plugins) {
      managementView.registerPlugin(plugin2);
    }

    List<InitializeForumPlugin> defaultPlugins = storage.getDefaultPlugins();
    for (InitializeForumPlugin plugin2 : defaultPlugins) {
      managementView.registerPlugin(plugin2);
    }

  }

  public void stop() {
    if (completionService != null) {
      completionService.shutdownNow();
    }
  }

  public void addMember(User user, UserProfile profileTemplate) throws Exception {
    boolean added = storage.populateUserProfile(user, profileTemplate, true);
    if (added) {
      forumStatisticsService.addMember(user.getUserName());
    }
  }

  @Override
  public void processEnabledUser(String userName, String email, boolean isEnabled) {
    storage.processEnabledUser(userName, email, isEnabled);
  }

  public void calculateDeletedUser(String userName) throws Exception {
    storage.calculateDeletedUser(userName);
  }

  /**
   * {@inheritDoc}
   */
  public void removeMember(User user) throws Exception {
    String userName = user.getUserName();
    if (storage.deleteUserProfile(userName)) {
      forumStatisticsService.removeMember(userName);
    }
  }

  public void createUserProfile(User user) throws Exception {

  }

  
  public void calculateDeletedGroup(String groupId, String groupName) throws Exception {
    storage.calculateDeletedGroup(groupId, groupName);
  }
  
  /**
   * {@inheritDoc}
   */
  public void updateUserProfile(User user) throws Exception {
    storage.populateUserProfile(user, null, false);
  }

  /**
   * @deprecated use {@link #updateUserProfile(User)}
   */
  public void saveEmailUserProfile(String userId, String email) throws Exception {
  }

  /**
   * {@inheritDoc}
   */
  public void saveCategory(Category category, boolean isNew) throws Exception {
    storage.saveCategory(category, isNew);
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.saveCategory(category);
      } catch (Exception e) {
        log.debug("Failed to run function saveCategory in the class ForumEventLifeCycle. ", e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void calculateModerator(String categoryPath, boolean isNew) throws Exception {
    storage.calculateModerator(categoryPath, false);
  }

  /**
   * {@inheritDoc}
   */
  public Category getCategory(String categoryId) {
    return storage.getCategory(categoryId);
  }
  
  public Category getCategoryIncludedSpace() {
    return storage.getCategoryIncludedSpace();
  }

  /**
   * {@inheritDoc}
   */
  public String[] getPermissionTopicByCategory(String categoryId, String type) throws Exception {
    return storage.getPermissionTopicByCategory(categoryId, type);
  }

  /**
   * {@inheritDoc}
   */
  public List<Category> getCategories() {
    return storage.getCategories();
  }

  /**
   * {@inheritDoc}
   */
  public Category removeCategory(String categoryId) throws Exception {
    List<Forum> listForums = storage.getForums(new ForumFilter(categoryId, true));
    for (Forum forum : listForums) {
      String forumId = forum.getId();
      List<Topic> listTopics = storage.getTopics(categoryId, forumId);
      for (Topic topic : listTopics) {
        String topicId = topic.getId();
        String topicActivityId = storage.getActivityIdForOwner(categoryId.concat("/").concat(forumId).concat("/").concat(topicId));
        for (ForumEventLifeCycle f : listeners_) {
          try {
            if (topic.getIsPoll()) {
              String pollActivityId = getActivityIdForOwnerPath(categoryId.concat("/").concat(forumId).concat("/").concat(topicId).concat("/").concat(topicId.replace(Utils.TOPIC, Utils.POLL)));
              f.removeActivity(pollActivityId);
            }
            f.removeActivity(topicActivityId);
          } catch (Exception e) {
            log.debug("Failed to run function removeActivity in the class ForumEventLifeCycle. ", e);
          }
        }
      }
    }
    return storage.removeCategory(categoryId);
  }

  /**
   * {@inheritDoc}
   */
  public void saveModOfCategory(List<String> moderatorCate, String userId, boolean isAdd) {
    storage.saveModOfCategory(moderatorCate, userId, isAdd);
  }

  /**
   * {@inheritDoc}
   */
  public void modifyForum(Forum forum, int type) throws Exception {
    storage.modifyForum(forum, type);
    List<Topic> topics = storage.getTopics(forum.getCategoryId(), forum.getId());
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.updateTopics(topics, (Utils.LOCK == type) ? forum.getIsLock() : forum.getIsClosed());
      } catch (Exception e) {
        log.debug("Failed to run function updateTopic in the class ForumEventLifeCycle. ", e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void saveForum(String categoryId, Forum forum, boolean isNew) throws Exception {
    storage.saveForum(categoryId, forum, isNew);
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.saveForum(forum);
      } catch (Exception e) {
        log.debug("Failed to run function saveForum in the class ForumEventLifeCycle. ", e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void saveModerateOfForums(List<String> forumPaths, String userName, boolean isDelete) throws Exception {
    storage.saveModerateOfForums(forumPaths, userName, isDelete);
  }

  /**
   * {@inheritDoc}
   */
  public void moveForum(List<Forum> forums, String destCategoryPath) throws Exception {
    storage.moveForum(forums, destCategoryPath);
  }

  public List<CategoryFilter> filterForumByName(String filterKey, String userName, int maxSize) throws Exception {
    return storage.filterForumByName(filterKey, userName, maxSize);
  }
  /**
   * {@inheritDoc}
   */
  public Forum getForum(String categoryId, String forumId){
    return storage.getForum(categoryId, forumId);
  }

  /**
   * {@inheritDoc}
   * @deprecated {@link #getForums(ForumFilter)}
   */
  public List<Forum> getForums(String categoryId, String strQuery) throws Exception {
    return storage.getForums(categoryId, strQuery);
  }

  /**
   * {@inheritDoc}
   * @deprecated {@link #getForums(ForumFilter)}
   */
  public List<Forum> getForumSummaries(String categoryId, String strQuery) throws Exception {
    return storage.getForumSummaries(categoryId, strQuery);
  }

  /**
   * {@inheritDoc}
   */
  public List<Forum> getForums(final ForumFilter filter) {
    return storage.getForums(filter);
  }

  /**
   * {@inheritDoc}
   */
  public Forum removeForum(String categoryId, String forumId) throws Exception {
    List<Topic> listTopics = getTopics(categoryId, forumId);
    if (listTopics == null)
      return null;
    for (Topic topic : listTopics) {
      String topicId = topic.getId();
      String topicActivityId = storage.getActivityIdForOwner(categoryId.concat("/").concat(forumId).concat("/").concat(topicId));
      for (ForumEventLifeCycle f : listeners_) {
        try {
          if (topic.getIsPoll()) {
            String pollActivityId = getActivityIdForOwnerPath(categoryId.concat("/").concat(forumId).concat("/").concat(topicId).concat("/").concat(topicId.replace(Utils.TOPIC, Utils.POLL)));
            f.removeActivity(pollActivityId);
          }
          f.removeActivity(topicActivityId);
        } catch (Exception e) {
          log.debug("Failed to run function removeActivity in the class ForumEventLifeCycle. ", e);
        }
      }
    }
    return storage.removeForum(categoryId, forumId);
  }

  /**
   * {@inheritDoc}
   */
  public void modifyTopic(List<Topic> topics, int type) {
    //update case
    List<Topic> editeds = new ArrayList<Topic>();
    Topic edited = null;
    for(Topic topic : topics) {
      //
      try {
        edited = getTopic(topic.getCategoryId(), topic.getForumId(), topic.getId(), "");
      } catch (Exception e) {
       log.warn("Ca not get Topic for " + topic.getId());
      }
      
      //
      switch (type) {
        case Utils.CLOSE: {
          edited.setEditedIsClosed(topic.getIsClosed());
          editeds.add(edited);
          break;
        }
        case Utils.LOCK: {
          edited.setEditedIsLock(topic.getIsLock());
          editeds.add(edited);
          break;
        }
        case Utils.WAITING: {//CENSORING
          edited.setEditedIsWaiting(topic.getIsWaiting());
          editeds.add(edited);
          break;
        }
        case Utils.ACTIVE: {//HIDDEN & Showing
          edited.setEditedIsActive(topic.getIsActive());
          editeds.add(edited);
          break;
        }
        case Utils.APPROVE: {
          edited.setEditedIsApproved(topic.getIsApproved());
          editeds.add(edited);
          break;
        }
        case Utils.CHANGE_NAME: {
          edited.setEditedTopicName(topic.getTopicName());
          editeds.add(edited);
          break;
        }
        case Utils.VOTE_RATING: {
          edited.setEditedVoteRating(topic.getVoteRating());
          editeds.add(edited);
          break;
        }
      }
    }
    
    storage.modifyTopic(topics, type);
    for (ForumEventLifeCycle f : listeners_) {
      for(Topic topic : editeds) {
        try {
          f.updateTopic(topic);
        } catch (Exception e) {
          log.debug("Failed to run function updateTopic in the class ForumEventLifeCycle. ", e);
        }
      }
    }
  }
  
  /**
   * {@inheritDoc}
   */
  public void modifyMergedTopic(List<Topic> topics, int type) {
    storage.modifyTopic(topics, type);
  }

  /**
   * 
   * @deprecated use {@link #saveTopic(String, String, Topic, boolean, boolean, MessageBuilder)}
   */
  @Deprecated
  public void saveTopic(String categoryId, String forumId, Topic topic, boolean isNew, boolean isMove, String defaultEmailContent) throws Exception {
    saveTopic(categoryId, forumId, topic, isNew, isMove, new MessageBuilder());
  }

  public void saveTopic(String categoryId, String forumId, Topic topic, boolean isNew, boolean isMove, MessageBuilder messageBuilder) throws Exception {
    //update case
    Topic edited = null;
    if(isNew == false) {
      edited = getTopic(categoryId, forumId, topic.getId(), "");
      edited.setEditedDescription(topic.getDescription());
      edited.setEditedTopicName(topic.getTopicName());
      edited.setEditedIsClosed(topic.getIsClosed());
      edited.setEditedIsLock(topic.getIsLock());
      edited.setEditedIsWaiting(topic.getIsWaiting());
      // check moderate topic then update all post
      if (! topic.getIsModeratePost() && edited.getIsModeratePost()) {
        // get all post
        List<Post> posts = storage.getPosts(new PostFilter(categoryId, forumId, topic.getId(), "false", null, null, null), 0, -1);
        modifyPost(posts, Utils.APPROVE);
      }
      // 
    }
    storage.saveTopic(categoryId, forumId, topic, isNew, isMove, messageBuilder);
    //
    for (ForumEventLifeCycle f : listeners_) {
      try {
        if (isNew && topic != null) {
          f.addTopic(topic);
        } else if (edited != null) {
          f.updateTopic(edited);
        }
      } catch (Exception e) {
        log.debug("Failed to run function addTopic/updateTopic in the class ForumEventLifeCycle. ", e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public Topic getTopic(String categoryId, String forumId, String topicId, String userRead) throws Exception {
    return storage.getTopic(categoryId, forumId, topicId, userRead);
  }

  /**
   * {@inheritDoc}
   */
  public void setViewCountTopic(String path, String userRead){
    storage.setViewCountTopic(path, userRead);
  }

  /**
   * {@inheritDoc}
   */
  public void writeReads() {
    storage.writeReads();
  }

  /**
   * {@inheritDoc}
   */
  public Topic getLastPostOfForum(String topicPath) throws Exception {
    return storage.getTopicSummary(topicPath, true);
  }

  /**
   * {@inheritDoc}
   */
  public Topic getTopicSummary(String topicPath) throws Exception {
    return storage.getTopicSummary(topicPath);
  }

  /**
   * {@inheritDoc}
   */
  public Topic getTopicByPath(String topicPath, boolean isLastPost) throws Exception {
    return storage.getTopicByPath(topicPath, isLastPost);
  }

  public Topic getTopicUpdate(Topic topic, boolean isSummary) throws Exception {
    return storage.getTopicUpdate(topic, isSummary);
  }

  /**
   * @deprecated use {@link ForumServiceImpl#getTopics(TopicFilter);
   */
  public LazyPageList<Topic> getTopicList(String categoryId, String forumId, String strQuery, String strOrderBy, int pageSize) throws Exception {
    return storage.getTopicList(categoryId, forumId, strQuery, strOrderBy, pageSize);
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getPageTopic(String categoryId, String forumId, String strQuery, String strOrderBy) throws Exception {
    return storage.getPageTopic(categoryId, forumId, strQuery, strOrderBy);
  }

  @Override
  public List<Topic> getTopics(String categoryId, String forumId) throws Exception {
    return storage.getTopics(categoryId, forumId);
  }

  @Override
  public ListAccess<Topic> getTopics(TopicFilter filter) throws Exception {
    return new TopicListAccess(TopicListAccess.Type.TOPICS, storage, filter);
  }

  /**
   * {@inheritDoc}
   */
  public void moveTopic(List<Topic> topics, String destForumPath, String mailContent, String link) throws Exception {
    storage.moveTopic(topics, destForumPath, mailContent, link);
    String toForumName = ((Forum) storage.getObjectNameByPath(destForumPath)).getForumName();
    String toCategoryName = ((Category) storage.getObjectNameByPath(Utils.getCategoryPath(destForumPath))).getCategoryName();
    for (ForumEventLifeCycle f : listeners_) {
      for (Topic topic : topics) {
        topic.setPath(destForumPath.concat("/").concat(topic.getId()));
        try {
          f.moveTopic(topic, toCategoryName, toForumName);
        } catch (Exception e) {
          log.debug("Failed to run function moveTopic in the class ForumEventLifeCycle. ", e);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public Topic removeTopic(String categoryId, String forumId, String topicId) throws Exception {
    String topicActivityId = storage.getActivityIdForOwner(categoryId.concat("/").concat(forumId).concat("/").concat(topicId));
    Topic topic = getTopic(categoryId, forumId, topicId, "");
    String pollActivityId = null;
    if (topic.getIsPoll())
      pollActivityId = getActivityIdForOwnerPath(categoryId.concat("/").concat(forumId).concat("/").concat(topicId).concat("/").concat(topicId.replace(Utils.TOPIC, Utils.POLL)));
    topic = storage.removeTopic(categoryId, forumId, topicId);
    for (ForumEventLifeCycle f : listeners_) {
      try {
        if (pollActivityId != null) {
          f.removeActivity(pollActivityId);
        }
        f.removeActivity(topicActivityId);
      } catch (Exception e) {
        log.debug("Failed to run function removeActivity in the class ForumEventLifeCycle. ", e);
      }
    }
    return topic;
  }

  /**
   * {@inheritDoc}
   */
  public Post getPost(String categoryId, String forumId, String topicId, String postId) throws Exception {
    return storage.getPost(categoryId, forumId, topicId, postId);
  }

  /**
   * {@inheritDoc}
   */
  public long getLastReadIndex(String path, String isApproved, String isHidden, String userLogin) throws Exception {
    return storage.getLastReadIndex(path, isApproved, isHidden, userLogin);
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getPostForSplitTopic(String topicPath) throws Exception {
    return storage.getPostForSplitTopic(topicPath);
  }

  /**
   * @deprecated use {@link ForumServiceImpl#getPosts(PostFilter filter);
   */
  public JCRPageList getPosts(String categoryId, String forumId, String topicId, String isApproved, String isHidden, String strQuery, String userLogin) throws Exception {
    return storage.getPosts(categoryId, forumId, topicId, isApproved, isHidden, strQuery, userLogin);
  }

  /**
   * {@inheritDoc}
   */
  public ListAccess<Post> getPosts(PostFilter filter) throws Exception {
    return new PostListAccess(PostListAccess.Type.POSTS, storage, filter);
  }

  /**
   * {@inheritDoc}
   */
  public long getAvailablePost(String categoryId, String forumId, String topicId, String isApproved, String isHidden, String userLogin) throws Exception {
    PostFilter filter = new PostFilter(categoryId, forumId, topicId, isApproved, isHidden, isHidden, userLogin);
    return Long.valueOf(storage.getPostsCount(filter));
  }

  /**
   * 
   * @deprecated use {@link #savePost(String, String, String, Post, boolean, MessageBuilder)}
   */
  @Deprecated
  public void savePost(String categoryId, String forumId, String topicId, Post post, boolean isNew, String defaultEmailContent) throws Exception {
    savePost(categoryId, forumId, topicId, post, isNew, new MessageBuilder());
  }

  public void savePost(String categoryId, String forumId, String topicId, Post post, boolean isNew, MessageBuilder messageBuilder) throws Exception {
    storage.savePost(categoryId, forumId, topicId, post, isNew, messageBuilder);
    if (post.getUserPrivate().length > 1)
      return;
    //
    if (post != null) {
      for (ForumEventLifeCycle f : listeners_) {
        try {
          if (isNew)
            f.addPost(post);
          else
            f.updatePost(post);
        } catch (Exception e) {
          log.debug("Failed to run function addPost/updatePost in the class ForumEventLifeCycle. ", e);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void modifyPost(List<Post> posts, int type){
    if (posts == null || posts.isEmpty()) {
      return;
    }
    storage.modifyPost(posts, type);
    for (ForumEventLifeCycle f : listeners_) {
      for(Post post : posts) {
        try {
          f.updatePost(post, type);
        } catch (Exception e) {
          log.debug("Failed to run function updatePost in the class ForumEventLifeCycle. ", e);
        }
      }
    }
  }

  /**
   * @deprecated use {@link #movePost(String[], String, boolean, String, String)}
   * Will remove on version 4.0.6
   */
  public void movePost(List<Post> posts, String destTopicPath, boolean isCreatNewTopic, String mailContent, String link) throws Exception {
    List<String> postPaths = new ArrayList<String>();
    for (Post p : posts) {
      postPaths.add(p.getPath());
    }
    movePost(postPaths.toArray(new String[postPaths.size()]), destTopicPath, isCreatNewTopic, mailContent, link);
  }

  /**
   * {@inheritDoc}
   */
  public void movePost(String[] postPaths, String destTopicPath, boolean isCreatNewTopic, String mailContent, String link) throws Exception {
    List<Post> posts = new ArrayList<Post>();
    List<String> srcPostActivityIds = new ArrayList<String>();
    for (int i = 0; i < postPaths.length; i++) {
      Post p = storage.getPost(Utils.getCategoryId(postPaths[i]), Utils.getForumId(postPaths[i]),
                               Utils.getTopicId(postPaths[i]), Utils.getPostId(postPaths[i]));
      posts.add(p);
      srcPostActivityIds.add(storage.getActivityIdForOwner(p.getPath()));
    }
    //
    storage.movePost(postPaths, destTopicPath, isCreatNewTopic, mailContent, link);

    //
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.movePost(posts, srcPostActivityIds, destTopicPath);
      } catch (Exception e) {
        log.warn("Failed to run function movePost in the class ForumEventLifeCycle. ");
        log.debug(e.getMessage(), e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void mergeTopic(String srcTopicPath, String destTopicPath, String mailContent, String link, String topicMergeTitle) throws Exception {
    String srcActivityId = storage.getActivityIdForOwner(srcTopicPath);
    String destActivityId = storage.getActivityIdForOwner(destTopicPath);
    //
    storage.mergeTopic(srcTopicPath, destTopicPath, mailContent, link);
    //
    Topic newTopic = storage.getTopicByPath(destTopicPath, false);
    newTopic.setTopicName(topicMergeTitle);
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.mergeTopic(newTopic, srcActivityId, destActivityId);
      } catch (Exception e) {
        log.debug("Failed to run function mergeTopic in the class ForumEventLifeCycle. ", e);
      }
    }
  }

  public void splitTopic(Topic newTopic, Post fistPost, List<String> postPathMove, String mailContent, String link) throws Exception {
    String srcTopicPath = Utils.getTopicPath(postPathMove.get(0));
    storage.splitTopic(newTopic, fistPost, postPathMove, mailContent, link);
    String srcActivityId = storage.getActivityIdForOwner(srcTopicPath);
    Topic srcTopic = storage.getTopicByPath(srcTopicPath, false);
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.splitTopic(newTopic, srcTopic, srcActivityId);
      } catch (Exception e) {
        log.debug("Failed to run function splitTopic in the class ForumEventLifeCycle. ", e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public Post removePost(String categoryId, String forumId, String topicId, String postId) {
    String topicActivityId = storage.getActivityIdForOwner(categoryId.concat("/").concat(forumId).concat("/").concat(topicId));
    String postActivityId = storage.getActivityIdForOwner(categoryId.concat("/").concat(forumId).concat("/").concat(topicId).concat("/").concat(postId));
    Post deleted = storage.removePost(categoryId, forumId, topicId, postId);
    
    //
    for (ForumEventLifeCycle f : listeners_) {
      try {
        f.removeComment(topicActivityId, postActivityId);
      } catch (Exception e) {
        log.debug("Failed to run function removeComment in the class ForumEventLifeCycle. ", e);
      }
    }
    
    return deleted;
  }

  /**
   * {@inheritDoc}
   */
  public Object getObjectNameByPath(String path) throws Exception {
    return storage.getObjectNameByPath(path);
  }

  /**
   * {@inheritDoc}
   */
  public Object getObjectNameById(String path, String type) throws Exception {
    return storage.getObjectNameById(path, type);
  }

  /**
   * {@inheritDoc}
   */
  public List<ForumLinkData> getAllLink(String strQueryCate, String strQueryForum) throws Exception {
    return storage.getAllLink(strQueryCate, strQueryForum);
  }

  /**
   * {@inheritDoc}
   */
  public String getForumHomePath() throws Exception {
    return storage.getDataLocation().getForumHomeLocation();
  }

  /**
   * {@inheritDoc}
   */
  public void addTag(List<Tag> tags, String userName, String topicPath) throws Exception {
    storage.addTag(tags, userName, topicPath);
  }

  /**
   * {@inheritDoc}
   */
  public List<Tag> getAllTags() throws Exception {
    return storage.getAllTags();
  }

  /**
   * {@inheritDoc}
   */
  public List<Tag> getMyTagInTopic(String[] tagIds) throws Exception {
    return storage.getMyTagInTopic(tagIds);
  }

  /**
   * {@inheritDoc}
   */
  public Tag getTag(String tagId) throws Exception {
    return storage.getTag(tagId);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getAllTagName(String strQuery, String userAndTopicId) throws Exception {
    return storage.getAllTagName(strQuery, userAndTopicId);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getTagNameInTopic(String userAndTopicId) throws Exception {
    return storage.getTagNameInTopic(userAndTopicId);
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getTopicByMyTag(String userIdAndtagId, String strOrderBy) throws Exception {
    return storage.getTopicByMyTag(userIdAndtagId, strOrderBy);
  }

  /**
   * {@inheritDoc}
   */
  public void saveTag(Tag newTag) throws Exception {
    storage.saveTag(newTag);
  }

  /**
   * {@inheritDoc}
   */
  public void unTag(String tagId, String userName, String topicPath) {
    storage.unTag(tagId, userName, topicPath);
  }

  /**
   * {@inheritDoc}
   */
  public void saveUserModerator(String userName, List<String> ids, boolean isModeCate) throws Exception {
    storage.saveUserModerator(userName, ids, isModeCate);
  }

  /**
   * {@inheritDoc}
   */
  public void saveUserProfile(UserProfile userProfile, boolean isOption, boolean isBan) throws Exception {
    storage.saveUserProfile(userProfile, isOption, isBan);
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile getUserInfo(String userName) throws Exception {
    return storage.getUserInfo(userName);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getUserModerator(String userName, boolean isModeCate) throws Exception {
    return storage.getUserModerator(userName, isModeCate);
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile getUserProfileManagement(String userName) throws Exception {
    return storage.getUserProfileManagement(userName);
  }

  /**
   * {@inheritDoc}
   */
  public void saveLastPostIdRead(String userId, String[] lastReadPostOfForum, String[] lastReadPostOfTopic) throws Exception {
    storage.saveLastPostIdRead(userId, lastReadPostOfForum, lastReadPostOfTopic);
  }

  /**
   * {@inheritDoc}
   */
  public void saveUserBookmark(String userName, String bookMark, boolean isNew) throws Exception {
    storage.saveUserBookmark(userName, bookMark, isNew);
  }

  /**
   * {@inheritDoc}
   */
  public void saveCollapsedCategories(String userName, String categoryId, boolean isAdd) throws Exception {
    storage.saveCollapsedCategories(userName, categoryId, isAdd);
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getPageListUserProfile() throws Exception {
    return storage.getPageListUserProfile();
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getPrivateMessage(String userName, String type) throws Exception {
    return storage.getPrivateMessage(userName, type);
  }

  /**
   * {@inheritDoc}
   */
  public long getNewPrivateMessage(String userName) throws Exception {
    return storage.getNewPrivateMessage(userName);
  }

  /**
   * {@inheritDoc}
   */
  public void removePrivateMessage(String messageId, String userName, String type) throws Exception {
    storage.removePrivateMessage(messageId, userName, type);
  }

  /**
   * {@inheritDoc}
   */
  public void saveReadMessage(String messageId, String userName, String type) throws Exception {
    storage.saveReadMessage(messageId, userName, type);
  }

  /**
   * {@inheritDoc}
   */
  public void savePrivateMessage(ForumPrivateMessage privateMessage) throws Exception {
    storage.savePrivateMessage(privateMessage);
  }

  /**
   * {@inheritDoc}
   */
  public ForumSubscription getForumSubscription(String userId) {
    return storage.getForumSubscription(userId);
  }

  /**
   * {@inheritDoc}
   */
  public void saveForumSubscription(ForumSubscription forumSubscription, String userId) throws Exception {
    storage.saveForumSubscription(forumSubscription, userId);
  }

  /**
   * 
   * @deprecated use {@link #getTopicsByDate(long, String)}
   */
  public JCRPageList getPageTopicOld(long date, String forumPatch) throws Exception {
    return storage.getPageTopicOld(date, forumPatch);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ListAccess<Topic> getTopicsByDate(long date, String forumPath) throws Exception {
    return new TopicListAccess(TopicListAccess.Type.TOPICS, storage, new TopicFilter(date, forumPath));
  }

  /**
   * {@inheritDoc}
   */
  public List<Topic> getAllTopicsOld(long date, String forumPatch) throws Exception {
    return storage.getAllTopicsOld(date, forumPatch);
  }

  /**
   * {@inheritDoc}
   */
  public long getTotalTopicOld(long date, String forumPatch) {
    return storage.getTotalTopicOld(date, forumPatch);
  }

  /**
   * 
   * @deprecated use {@link #getTopicsByUser(TopicFilter, int, int)}
   */
  public JCRPageList getPageTopicByUser(String userName, boolean isMod, String strOrderBy) throws Exception {
    return storage.getPageTopicByUser(userName, isMod, strOrderBy);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public ListAccess<Topic> getPageTopicByUser(TopicFilter filter) throws Exception {
    return new TopicListAccess(TopicListAccess.Type.TOPICS, storage, filter);
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getPagePostByUser(String userName, String userId, boolean isMod, String strOrderBy) throws Exception {
    return storage.getPagePostByUser(userName, userId, isMod, strOrderBy);
  }

  /**
   * {@inheritDoc}
   */
  public ForumStatistic getForumStatistic() throws Exception {
    return storage.getForumStatistic();
  }

  /**
   * {@inheritDoc}
   */
  public void saveForumStatistic(ForumStatistic forumStatistic) throws Exception {
    storage.saveForumStatistic(forumStatistic);
  }

  /**
   * {@inheritDoc}
   */
  public void updateStatisticCounts(long topicCount, long postCount) throws Exception {
    storage.updateStatisticCounts(topicCount, postCount);
  }

  /**
   * {@inheritDoc}
   */
  public List<ForumSearchResult> getQuickSearch(String textQuery, String type, String pathQuery, String userId, List<String> listCateIds, List<String> listForumIds, List<String> forumIdsOfModerator) throws Exception {
    return storage.getQuickSearch(textQuery, type, pathQuery, userId, listCateIds, listForumIds, forumIdsOfModerator);
  }

  /**
   * {@inheritDoc}
   */
  public List<ForumSearchResult> getAdvancedSearch(ForumEventQuery eventQuery, List<String> listCateIds, List<String> listForumIds){
    return storage.getAdvancedSearch(eventQuery, listCateIds, listForumIds);
  }

  /**
   * {@inheritDoc}
   */
  public ForumAdministration getForumAdministration() throws Exception {
    return storage.getForumAdministration();
  }

  /**
   * {@inheritDoc}
   */
  public void saveForumAdministration(ForumAdministration forumAdministration) throws Exception {
    storage.saveForumAdministration(forumAdministration);
  }

  /**
   * {@inheritDoc}
   */
  public void addWatch(int watchType, String path, List<String> values, String currentUser) throws Exception {
    storage.addWatch(watchType, path, values, currentUser);
  }

  /**
   * {@inheritDoc}
   */
  public void removeWatch(int watchType, String path, String values) throws Exception {
    storage.removeWatch(watchType, path, values);
  }

  /**
   * {@inheritDoc}
   */
  public List<ForumSearchResult> getJobWattingForModerator(String[] paths){
    return storage.getJobWattingForModerator(paths);
  }

  /**
   * {@inheritDoc}
   */
  public int getJobWattingForModeratorByUser(String userId) throws Exception {
    return storage.getJobWattingForModeratorByUser(userId);
  }

  @Override
  public void updateLoggedinUsers(String repoName) throws Exception {
    //
    LinkedList<UserLoginLogEntry> queue = queueMap_.get(repoName);
    if (queue == null || queue.size() == 0) {
      return;
    }
    queueMap_.remove(repoName);
    //
    for (UserLoginLogEntry loginEntry_ : queue) {
      storage.updateLastLoginDate(loginEntry_.userName);
    }
    //
    UserLoginLogEntry loginEntry = queue.getFirst();
    int maxOnline = loginEntry.totalOnline;
    Calendar timestamp = loginEntry.loginTime;

    ForumStatistic stats = storage.getForumStatistic();

    int jcrMostOnline = getMaxOnlineFromStorage(stats);
    if (maxOnline > jcrMostOnline) {
      stats.setMostUsersOnline(maxOnline + "," + timestamp.getTimeInMillis());
      storage.saveForumStatistic(stats);
    }
  }
  
  private int getMaxOnlineFromStorage(ForumStatistic stats) throws Exception {
    int mostOnline = 0;
    String mostUsersOnline = stats.getMostUsersOnline();
    if (mostUsersOnline != null && mostUsersOnline.length() > 0) {
      String[] array = mostUsersOnline.split(",");
      try {
        mostOnline = Integer.parseInt(array[0].trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return mostOnline;
  }

  /**
   * {@inheritDoc}
   */
  public void updateLoggedinUsers() throws Exception {
    updateLoggedinUsers(Utils.DEFAULT_TENANT_NAME);
  }
  /**
   * {@inheritDoc}
   */
  public void userLogin(String userId) throws Exception {
    userLogin(Utils.DEFAULT_TENANT_NAME, userId);
    userStateService.ping(userId);
  }
  
  public void userLogin(String repoName, String userId) throws Exception {
    //
    if (userStateService.isOnline(userId)) {
      return;
    }
    //
    LinkedList<UserLoginLogEntry> queue = queueMap_.get(repoName);
    if (queue == null) {
      queue = new LinkedList<UserLoginLogEntry>();
    }
    int onlineSize = userStateService.online().size() + 1;
    UserLoginLogEntry loginEntry = new UserLoginLogEntry(userId, Math.max(onlineSize, queue.size() + 1));
    int latestMaxOnline = (queue.size() > 0) ? queue.getFirst().totalOnline : 0;
    if (latestMaxOnline < onlineSize) {
      queue.addFirst(loginEntry);
    } else {
      queue.addLast(loginEntry);
    }
    //
    queueMap_.put(repoName, queue);
  }

  /**
   * {@inheritDoc}
   */
  public void userLogout(String userId) throws Exception {
    removeCacheUserProfile(userId);
  }

  /**
   * {@inheritDoc}
   */
  public boolean isOnline(String userId) throws Exception {
    return userStateService.isOnline(userId);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getOnlineUsers() throws Exception {
    List<String> onlineUsers = new ArrayList<String>();
    List<UserStateModel> onlines = userStateService.online();
    for (UserStateModel model : onlines) {
      onlineUsers.add(model.getUserId());
    }
    
    return onlineUsers;
  }

  /**
   * {@inheritDoc}
   */
  public String getLastLogin() throws Exception {
    List<String> onlineUsers = getOnlineUsers();
    int size = onlineUsers.size();
    return (size > 0) ? onlineUsers.get(size - 1) : "";
  }

  /**
   * {@inheritDoc}
   */
  public SendMessageInfo getMessageInfo(String name) throws Exception {
    return storage.getMessageInfo(name);
  }

  /**
   * {@inheritDoc}
   */
  public Iterator<SendMessageInfo> getPendingMessages() throws Exception {
    return storage.getPendingMessages();
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList searchUserProfile(String userSearch) throws Exception {
    return storage.searchUserProfile(userSearch);
  }

  /**
   * {@inheritDoc}
   */
  public boolean isAdminRole(String userName) throws Exception {
    return storage.isAdminRole(userName);
  }
  
  /**
   * {@inheritDoc}
   */
  public boolean isAdminRoleConfig(String userName) throws Exception {
    return storage.isAdminRoleConfig(userName);
  }

  /**
   * {@inheritDoc}
   */
  public List<Post> getNewPosts(int number) throws Exception {
    return storage.getNewPosts(number);
  }

  /**
   * {@inheritDoc}
   */
  public List<Post> getRecentPostsForUser(String userName, int number) throws Exception {
    return storage.getRecentPostsForUser(userName, number);
  }

  public NodeIterator search(String queryString) throws Exception {
    return storage.search(queryString);
  }

  /**
   * {@inheritDoc}
   */
  public void evaluateActiveUsers(String query) {
    storage.evaluateActiveUsers(query);
  }

  /**
   * {@inheritDoc}
   */
  public void updateTopicAccess(String userId, String topicId) {
    storage.updateTopicAccess(userId, topicId);
  }

  /**
   * {@inheritDoc}
   */
  public void updateForumAccess(String userId, String forumId){
    storage.updateForumAccess(userId, forumId);
  }

  /**
   * {@inheritDoc}
   */
  public void writeViews() {
    storage.writeViews();
  }

  /**
   * {@inheritDoc}
   */
  public Object exportXML(String categoryId, String forumId, List<String> objectIds, String nodePath, ByteArrayOutputStream bos, boolean isExportAll) throws Exception {
    return storage.exportXML(categoryId, forumId, objectIds, nodePath, bos, isExportAll);
  }

  /**
   * {@inheritDoc}
   */
  public List<UserProfile> getQuickProfiles(List<String> userList) throws Exception {
    return storage.getQuickProfiles(userList);
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile getQuickProfile(String userName) throws Exception {
    return storage.getQuickProfile(userName);
  }

  /**
   * {@inheritDoc}
   */
  public String getScreenName(String userName) throws Exception {
    return storage.getScreenName(userName);
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile getUserInformations(UserProfile userProfile) throws Exception {
    return storage.getUserInformations(userProfile);
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile getDefaultUserProfile(String userName, String ip) throws Exception {
    UserProfile userProfile = storage.getDefaultUserProfile(userName, null);
    if (!userProfile.getIsBanned() && ip != null) {
      userProfile.setIsBanned(storage.isBanIp(ip));
    }
    return userProfile;
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile updateUserProfileSetting(UserProfile userProfile) throws Exception {
    return storage.updateUserProfileSetting(userProfile);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getBookmarks(String userName) throws Exception {
    return storage.getBookmarks(userName);
  }

  /**
   * {@inheritDoc}
   */
  public UserProfile getUserSettingProfile(String userName) throws Exception {
    return storage.getUserSettingProfile(userName);
  }

  /**
   * {@inheritDoc}
   */
  public void saveUserSettingProfile(UserProfile userProfile) throws Exception {
    storage.saveUserSettingProfile(userProfile);
  }

  /**
   * {@inheritDoc}
   */
  public void importXML(String nodePath, ByteArrayInputStream bis, int typeImport) throws Exception {
    storage.importXML(nodePath, bis, typeImport);
  }

  /**
   * {@inheritDoc}
  public void updateDataImported() throws Exception{
    storage.updateDataImported();
  }
   */

  /**
   * {@inheritDoc}
   */
  public void updateForum(String path) throws Exception {
    storage.updateForum(path);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getBanList() throws Exception {
    return storage.getBanList();
  }

  /**
   * {@inheritDoc}
   */
  public boolean addBanIP(String ip) throws Exception {
    return storage.addBanIP(ip);
  }

  /**
   * {@inheritDoc}
   */
  public void removeBan(String ip) throws Exception {
    storage.removeBan(ip);
  }

  /**
   * {@inheritDoc}
   */
  public JCRPageList getListPostsByIP(String ip, String strOrderBy) throws Exception {
    return storage.getListPostsByIP(ip, strOrderBy);
  }

  /**
   * {@inheritDoc}
   */
  public List<String> getForumBanList(String forumId) throws Exception {
    return storage.getForumBanList(forumId);
  }

  /**
   * {@inheritDoc}
   */
  public boolean addBanIPForum(String ip, String forumId) throws Exception {
    return storage.addBanIPForum(ip, forumId);
  }

  /**
   * {@inheritDoc}
   */
  public void removeBanIPForum(String ip, String forumId) throws Exception {
    storage.removeBanIPForum(ip, forumId);
  }

  /**
   * {@inheritDoc}
   */
  public ForumAttachment getUserAvatar(String userName) throws Exception {
    return storage.getUserAvatar(userName);
  }

  /**
   * {@inheritDoc}
   */
  public void saveUserAvatar(String userId, ForumAttachment fileAttachment) throws Exception {
    storage.saveUserAvatar(userId, fileAttachment);
  }

  /**
   * {@inheritDoc}
   */
  public void setDefaultAvatar(String userName) {
    storage.setDefaultAvatar(userName);
  }

  /**
   * {@inheritDoc}
   */
  public List<Watch> getWatchByUser(String userId) throws Exception {
    return storage.getWatchByUser(userId);
  }

  /**
   * {@inheritDoc}
   */
  public void updateEmailWatch(List<String> listNodeId, String newEmailAdd, String userId) throws Exception {
    storage.updateEmailWatch(listNodeId, newEmailAdd, userId);
  }

  /**
   * {@inheritDoc}
   */
  public List<PruneSetting> getAllPruneSetting() throws Exception {
    return storage.getAllPruneSetting();
  }

  /**
   * {@inheritDoc}
   */
  public void savePruneSetting(PruneSetting pruneSetting) throws Exception {
    storage.savePruneSetting(pruneSetting);
  }

  /**
   * {@inheritDoc}
   */
  public PruneSetting getPruneSetting(String forumPath) throws Exception {
    return storage.getPruneSetting(forumPath);
  }

  /**
   * {@inheritDoc}
   */
  public void runPrune(PruneSetting pSetting) throws Exception {
    storage.runPrune(pSetting);
  }

  /**
   * {@inheritDoc}
   */
  public void runPrune(String forumPath) throws Exception {
    storage.runPrune(forumPath);
  }

  /**
   * {@inheritDoc}
   */
  public long checkPrune(PruneSetting pSetting) throws Exception {
    return storage.checkPrune(pSetting);
  }

  /**
   * {@inheritDoc}
   */
  public void updateUserProfileInfo(String name) throws Exception {
    storage.updateUserProfileInfo(name);
  }

  public DataStorage getStorage() {
    return storage;
  }

  public void setStorage(DataStorage storage) {
    this.storage = storage;
  }

  public ForumServiceManaged getManagementView() {
    return managementView;
  }

  public void setManagementView(ForumServiceManaged managementView) {
    this.managementView = managementView;
  }

  public ForumStatisticsService getForumStatisticsService() {
    return forumStatisticsService;
  }

  public void setForumStatisticsService(ForumStatisticsService forumStatisticsService) {
    this.forumStatisticsService = forumStatisticsService;
  }

  public JobSchedulerService getJobSchedulerService() {
    return jobSchedulerService;
  }

  public void setJobSchedulerService(JobSchedulerService jobSchedulerService) {
    this.jobSchedulerService = jobSchedulerService;
  }

  public InputStream createForumRss(String objectId, String link) throws Exception {
    return storage.createForumRss(objectId, link);
  }

  public InputStream createUserRss(String userId, String link) throws Exception {
    return storage.createUserRss(userId, link);
  }

  public void addListenerPlugin(ForumEventListener listener) throws Exception {
    listeners_.add(listener);
  }
  
  public void removeCacheUserProfile(String userName) {
    storage.removeCacheUserProfile(userName);
  }

  public void saveActivityIdForOwnerId(String ownerId,  String activityId) {
    storage.saveActivityIdForOwner(ownerId, Utils.TOPIC, activityId);
  }

  public void saveActivityIdForOwnerPath(String ownerPath, String activityId) {
    storage.saveActivityIdForOwner(ownerPath, activityId);
  }

  public String getActivityIdForOwnerId(String ownerId) {
    return storage.getActivityIdForOwner(ownerId, Utils.TOPIC);
  }

  public String getActivityIdForOwnerPath(String ownerPath) {
    return storage.getActivityIdForOwner(ownerPath);
  }
  
  public void saveCommentIdForOwnerId(String ownerId,  String commentId) {
    storage.saveActivityIdForOwner(ownerId, Utils.POST, commentId);
  }

  public void saveCommentIdForOwnerPath(String ownerPath, String commentId) {
    storage.saveActivityIdForOwner(ownerPath, commentId);
  }

  public String getCommentIdForOwnerId(String ownerId) {
    return storage.getActivityIdForOwner(ownerId, Utils.POST);
  }

  public String getCommentIdForOwnerPath(String ownerPath) {
    return storage.getActivityIdForOwner(ownerPath);
  }

  @Override
  public ListAccess<UserProfile> searchUserProfileByFilter(UserProfileFilter userProfileFilter) throws Exception {
    return new UserProfileListAccess(storage, userProfileFilter);
  }

}
