package com.quickblox.q_municate_core.qb.commands;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.quickblox.chat.model.QBDialog;
import com.quickblox.core.request.QBPagedRequestBuilder;
import com.quickblox.q_municate_core.core.command.ServiceCommand;
import com.quickblox.q_municate_core.models.GroupDialog;
import com.quickblox.q_municate_core.qb.helpers.QBGroupChatHelper;
import com.quickblox.q_municate_core.service.QBService;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate_core.utils.ConstsCore;
import com.quickblox.q_municate_core.utils.UserFriendUtils;
import com.quickblox.q_municate_db.models.User;
import com.quickblox.users.QBUsers;
import com.quickblox.users.model.QBUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class QBLoadGroupDialogCommand extends ServiceCommand {

    private QBGroupChatHelper multiChatHelper;

    public QBLoadGroupDialogCommand(Context context, QBGroupChatHelper chatHelper, String successAction,
            String failAction) {
        super(context, successAction, failAction);
        this.multiChatHelper = chatHelper;
    }

    public static void start(Context context, QBDialog dialog) {
        Intent intent = new Intent(QBServiceConsts.LOAD_GROUP_DIALOG_ACTION, null, context, QBService.class);
        intent.putExtra(QBServiceConsts.EXTRA_DIALOG, dialog);
        context.startService(intent);
    }

    @Override
    public Bundle perform(Bundle extras) throws Exception {
        QBDialog dialog = (QBDialog) extras.getSerializable(QBServiceConsts.EXTRA_DIALOG);

        GroupDialog groupDialog = new GroupDialog(dialog);

        List<Integer> participantIdsList = dialog.getOccupants();
        List<Integer> onlineParticipantIdsList = multiChatHelper.getRoomOnlineParticipantList(dialog.getRoomJid());

        QBPagedRequestBuilder requestBuilder = new QBPagedRequestBuilder();
        requestBuilder.setPage(ConstsCore.FL_FRIENDS_PAGE_NUM);
        requestBuilder.setPerPage(ConstsCore.FL_FRIENDS_PER_PAGE);

        Bundle requestParams = new Bundle();
        List<QBUser> userList = QBUsers.getUsersByIDs(participantIdsList, requestBuilder, requestParams);
        Map<Integer, User> friendMap = UserFriendUtils.createUserMap(userList);
        for (Integer onlineParticipantId : onlineParticipantIdsList) {
            User user = friendMap.get(onlineParticipantId);
            if (user != null) {
                user.setOnline(true);
            }
        }

        ArrayList<User> friendList = new ArrayList<User>(friendMap.values());
        Collections.sort(friendList, new UserComparator());
        groupDialog.setOccupantList(friendList);

        Bundle params = new Bundle();
        params.putSerializable(QBServiceConsts.EXTRA_GROUP_DIALOG, groupDialog);
        return params;
    }

    private class UserComparator implements Comparator<User> {

        @Override
        public int compare(User firstUser, User secondUser) {
            if (firstUser.getFullName() == null || secondUser.getFullName() == null) {
                return ConstsCore.ZERO_INT_VALUE;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(firstUser.getFullName(), secondUser.getFullName());
        }
    }
}