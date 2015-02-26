package com.quickblox.q_municate_core.qb.helpers;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.quickblox.chat.QBChatService;
import com.quickblox.chat.QBRoster;
import com.quickblox.chat.listeners.QBRosterListener;
import com.quickblox.chat.listeners.QBSubscriptionListener;
import com.quickblox.chat.model.QBChatMessage;
import com.quickblox.chat.model.QBDialog;
import com.quickblox.chat.model.QBPresence;
import com.quickblox.chat.model.QBRosterEntry;
import com.quickblox.core.exception.QBResponseException;
import com.quickblox.core.request.QBPagedRequestBuilder;
import com.quickblox.q_municate_core.R;
import com.quickblox.q_municate_core.db.managers.ChatDatabaseManager;
import com.quickblox.q_municate_core.models.Friend;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate_core.utils.ChatNotificationUtils;
import com.quickblox.q_municate_core.utils.ErrorUtils;
import com.quickblox.q_municate_core.utils.UserFriendUtils;
import com.quickblox.q_municate_db.managers.DatabaseManager;
import com.quickblox.q_municate_db.models.Role;
import com.quickblox.q_municate_db.models.User;
import com.quickblox.users.QBUsers;
import com.quickblox.users.model.QBUser;

import org.jivesoftware.smack.packet.RosterPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class QBFriendListHelper extends BaseHelper {

    public static final String RELATION_STATUS_NONE = "none";
    public static final String RELATION_STATUS_TO = "to";
    public static final String RELATION_STATUS_FROM = "from";
    public static final String RELATION_STATUS_BOTH = "both";
    public static final String RELATION_STATUS_REMOVE = "remove";
    public static final String RELATION_STATUS_ALL_USERS = "all_users";
    public static final int VALUE_RELATION_STATUS_ALL_USERS = 10;
    private static final String TAG = QBFriendListHelper.class.getSimpleName();
    private static final String PRESENCE_CHANGE_ERROR = "Presence change error: could not find friend in DB by id = ";
    private static final String ENTRIES_UPDATING_ERROR = "Failed to update friends list";
    private static final String ENTRIES_ADDED_ERROR = "Failed to add friends to list";
    private static final String ENTRIES_DELETED_ERROR = "Failed to delete friends";
    private static final String SUBSCRIPTION_ERROR = "Failed to confirm subscription";
    private static final String ROSTER_INIT_ERROR = "ROSTER isn't initialized. Please make relogin";

    private static final int FIRST_PAGE = 1;
    // Default value equals 0, bigger value allows to prevent overwriting of presence that contains status
    // with presence that is sent on login by default
    private static final int STATUS_PRESENCE_PRIORITY = 1;

    private QBRestHelper restHelper;
    private QBRoster roster;
    private QBPrivateChatHelper privateChatHelper;

    public QBFriendListHelper(Context context) {
        super(context);
    }

    public void init(QBPrivateChatHelper privateChatHelper) {
        this.privateChatHelper = privateChatHelper;
        restHelper = new QBRestHelper(context);
        roster = QBChatService.getInstance().getRoster(QBRoster.SubscriptionMode.mutual,
                new SubscriptionListener());
        roster.setSubscriptionMode(QBRoster.SubscriptionMode.mutual);
        roster.addRosterListener(new RosterListener());
    }

    public void inviteFriend(int userId) throws Exception {
        if (isNotInvited(userId)) {
            invite(userId);
        }
    }

    public void addFriend(int userId) throws Exception {
//        if (isNewFriend(userId)) {
//            acceptFriend(userId);
//        } else {
//            createFriend(userId, false);
//            invite(userId);
//        }
    }

    public void invite(int userId) throws Exception {
        sendInvitation(userId);

        QBChatMessage chatMessage = ChatNotificationUtils.createNotificationMessageForFriendsRequest(context);
        sendNotificationToFriend(chatMessage, userId);
    }

    private void sendNotificationToFriend(QBChatMessage chatMessage, int userId) throws QBResponseException {
        QBDialog existingPrivateDialog = privateChatHelper.createPrivateDialogIfNotExist(userId,
                chatMessage.getBody());
        privateChatHelper.sendPrivateMessage(chatMessage, userId, existingPrivateDialog.getDialogId());
    }

    public void acceptFriend(int userId) throws Exception {
        roster.confirmSubscription(userId);

        QBChatMessage chatMessage = ChatNotificationUtils.createNotificationMessageForAcceptFriendsRequest(
                context);
        sendNotificationToFriend(chatMessage, userId);
    }

    public void rejectFriend(int userId) throws Exception {
        roster.reject(userId);
        clearRosterEntry(userId);
        deleteFriend(userId);

        QBChatMessage chatMessage = ChatNotificationUtils.createNotificationMessageForRejectFriendsRequest(
                context);
        sendNotificationToFriend(chatMessage, userId);
    }

    private void clearRosterEntry(int userId) throws Exception {
        QBRosterEntry rosterEntry = roster.getEntry(userId);
        if (rosterEntry != null && roster.contains(userId)) {
            roster.removeEntry(rosterEntry);
        }
    }

    public void removeFriend(int userId) throws Exception {
        roster.unsubscribe(userId);
        clearRosterEntry(userId);
        deleteFriend(userId);

        QBChatMessage chatMessage = ChatNotificationUtils.createNotificationMessageForRemoveFriendsRequest(
                context);
        sendNotificationToFriend(chatMessage, userId);
    }

    private boolean isNotInvited(int userId) {
        return !isInvited(userId);
    }

    private boolean isInvited(int userId) {
        QBRosterEntry rosterEntry = roster.getEntry(userId);
        if (rosterEntry == null) {
            return false;
        }
        boolean isSubscribedToUser = rosterEntry.getType() == RosterPacket.ItemType.from;
        boolean isBothSubscribed = rosterEntry.getType() == RosterPacket.ItemType.both;
        return isSubscribedToUser || isBothSubscribed;
    }

    private void sendInvitation(int userId) throws Exception {
        if (roster.contains(userId)) {
            roster.subscribe(userId);
        } else {
            roster.createEntry(userId, null);
        }
    }

    public Collection<Integer> updateFriendList() throws QBResponseException {
        Collection<Integer> userIdsList = new ArrayList<>();

        if (roster != null) {
            if (!roster.getEntries().isEmpty()) {
                userIdsList = UserFriendUtils.getUserIdsFromRoster(roster.getEntries());
                updateFriends(userIdsList);
            }
        } else {
            ErrorUtils.logError(TAG, ROSTER_INIT_ERROR);
        }

        return userIdsList;
    }

    private void updateFriends(Collection<Integer> friendIdsList) throws QBResponseException {
        List<QBUser> usersList = loadUsers(friendIdsList);

        //        fillUsersWithRosterData(usersList);

        saveUsersAndFriends(usersList);
    }

    private void updateFriends1(Collection<Integer> userIdsList) throws QBResponseException {
        for (Integer userId : userIdsList) {
            updateFriend(userId);
        }
    }

    private void updateFriend(int userId) throws QBResponseException {
        QBRosterEntry rosterEntry = roster.getEntry(userId);

        User newUser = restHelper.loadUser(userId);

        if (newUser == null) {
            return;
        }

        Friend friend = UserFriendUtils.createFriend(rosterEntry);

        newUser.setOnline(isFriendOnline(roster.getPresence(userId)));

        saveUser(newUser);
        saveFriend(newUser);

        fillUserOnlineStatus(newUser);
    }

    private void createFriend(int userId, boolean isNewFriendStatus) throws QBResponseException {
        User user = restHelper.loadUser(userId);

        if (user == null) {
            return;
        }

        Friend friend = UserFriendUtils.createFriend(userId);
        friend.setNewFriendStatus(isNewFriendStatus);
        fillUserOnlineStatus(user);

        saveUser(user);
        saveFriend(user);
    }

    private List<QBUser> loadUsers(Collection<Integer> userIds) throws QBResponseException {
        QBPagedRequestBuilder requestBuilder = new QBPagedRequestBuilder();
        requestBuilder.setPage(FIRST_PAGE);
        requestBuilder.setPerPage(userIds.size());

        Bundle params = new Bundle();
        return QBUsers.getUsersByIDs(userIds, requestBuilder, params);
    }

    private void fillUsersWithRosterData(List<User> usersList) {
        for (User user : usersList) {
            fillUserOnlineStatus(user);
        }
    }

    private void fillUserOnlineStatus(User user) {
        if (roster != null) {
            QBPresence presence = roster.getPresence(user.getUserId());
            fillUserOnlineStatus(user, presence);
        }
    }

    private void fillUserOnlineStatus(User user, QBPresence presence) {
        if (isFriendOnline(presence)) {
            user.setOnline(true);
        } else {
            user.setOnline(false);
        }
    }

    private boolean isFriendOnline(QBPresence presence) {
        return QBPresence.Type.online.equals(presence.getType());
    }

    private void saveUser(User user) {
        DatabaseManager.getInstance().getUserManager().createIfNotExists(user);

//        UsersDatabaseManager.saveUser(context, user);
    }

    private void saveUsersAndFriends(Collection<QBUser> usersCollection) {
        Role sampleRole = DatabaseManager.getInstance().getRoleManager().getByRoleType(Role.Type.SIMPLE_ROLE);
        for (QBUser qbUser : usersCollection) {
            User user = UserFriendUtils.createLocalUser(qbUser, sampleRole);
            DatabaseManager.getInstance().getUserManager().createIfNotExists(user);
            DatabaseManager.getInstance().getFriendManager().createIfNotExists(new com.quickblox.q_municate_db.models.Friend(user));
        }
//        UsersDatabaseManager.savePeople(context, usersList);
    }

    private void saveFriend(User user) {
        DatabaseManager.getInstance().getFriendManager().createIfNotExists(new com.quickblox.q_municate_db.models.Friend(user));
    }

    private void deleteFriend(int userId) {
        ChatDatabaseManager.deleteFriendById(context, userId);
    }

    private void deleteFriends(Collection<Integer> userIdsList) throws QBResponseException {
        for (Integer userId : userIdsList) {
            deleteFriend(userId);
        }
    }

//    private boolean isNewFriend(int userId) {
//        return UsersDatabaseManager.isFriendWithStatusNew(context, userId);
//    }

    private void notifyContactRequest(int userId) {
        Intent intent = new Intent(QBServiceConsts.GOT_CONTACT_REQUEST);

        intent.putExtra(QBServiceConsts.EXTRA_MESSAGE, context.getResources().getString(
                R.string.frl_friends_contact_request));
        intent.putExtra(QBServiceConsts.EXTRA_USER_ID, userId);

        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private class RosterListener implements QBRosterListener {

        @Override
        public void entriesDeleted(Collection<Integer> userIdsList) {
            try {
                deleteFriends(userIdsList);
            } catch (QBResponseException e) {
                Log.e(TAG, ENTRIES_DELETED_ERROR, e);
            }
        }

        @Override
        public void entriesAdded(Collection<Integer> userIdsList) {
        }

        @Override
        public void entriesUpdated(Collection<Integer> userIdsList) {
            try {
                updateFriends1(userIdsList);
            } catch (QBResponseException e) {
                Log.e(TAG, ENTRIES_UPDATING_ERROR, e);
            }
        }

        @Override
        public void presenceChanged(QBPresence presence) {
            User user = DatabaseManager.getInstance().getUserManager().get(presence.getUserId());

//            User user = UsersDatabaseManager.getUserById(context, presence.getUserId());

            if (user == null) {
                ErrorUtils.logError(TAG, PRESENCE_CHANGE_ERROR + presence.getUserId());
            } else {
                fillUserOnlineStatus(user, presence);

//                UsersDatabaseManager.saveUser(context, user);
            }
        }
    }

    private class SubscriptionListener implements QBSubscriptionListener {

        @Override
        public void subscriptionRequested(int userId) {
            try {
                createFriend(userId, true);
                notifyContactRequest(userId);
            } catch (Exception e) {
                Log.e(TAG, SUBSCRIPTION_ERROR, e);
            }
        }
    }
}