/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.transaction;

import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF;

import com.android.mms.R;
import com.android.mms.LogTag;
import com.android.mms.data.Contact;
import com.android.mms.data.Conversation;
import com.android.mms.ui.ComposeMessageActivity;
import com.android.mms.ui.ConversationList;
import com.android.mms.ui.MessagingPreferenceActivity;
import com.android.mms.util.AddressUtils;
import com.android.mms.util.DownloadManager;

import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPersister;
import android.database.sqlite.SqliteWrapper;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.widget.Toast;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * This class is used to update the notification indicator. It will check whether
 * there are unread messages. If yes, it would show the notification indicator,
 * otherwise, hide the indicator.
 */
public class MessagingNotification {

    private static final String TAG = LogTag.APP;

    private static final int NOTIFICATION_ID = 123;
    public static final int MESSAGE_FAILED_NOTIFICATION_ID = 789;
    public static final int DOWNLOAD_FAILED_NOTIFICATION_ID = 531;
    /**
     * This is the volume at which to play the in-conversation notification sound,
     * expressed as a fraction of the system notification volume.
     */
    private static final float IN_CONVERSATION_NOTIFICATION_VOLUME = 0.25f;

    // This must be consistent with the column constants below.
    private static final String[] MMS_STATUS_PROJECTION = new String[] {
        Mms.THREAD_ID, Mms.DATE, Mms._ID, Mms.SUBJECT, Mms.SUBJECT_CHARSET };

    // This must be consistent with the column constants below.
    private static final String[] SMS_STATUS_PROJECTION = new String[] {
        Sms.THREAD_ID, Sms.DATE, Sms.ADDRESS, Sms.SUBJECT, Sms.BODY };

    // These must be consistent with MMS_STATUS_PROJECTION and
    // SMS_STATUS_PROJECTION.
    private static final int COLUMN_THREAD_ID   = 0;
    private static final int COLUMN_DATE        = 1;
    private static final int COLUMN_MMS_ID      = 2;
    private static final int COLUMN_SMS_ADDRESS = 2;
    private static final int COLUMN_SUBJECT     = 3;
    private static final int COLUMN_SUBJECT_CS  = 4;
    private static final int COLUMN_SMS_BODY    = 4;

    private static final String[] SMS_THREAD_ID_PROJECTION = new String[] { Sms.THREAD_ID };
    private static final String[] MMS_THREAD_ID_PROJECTION = new String[] { Mms.THREAD_ID };

    private static final String NEW_INCOMING_SM_CONSTRAINT =
            "(" + Sms.TYPE + " = " + Sms.MESSAGE_TYPE_INBOX
            + " AND " + Sms.SEEN + " = 0)";

    private static final String NEW_DELIVERY_SM_CONSTRAINT =
        "(" + Sms.TYPE + " = " + Sms.MESSAGE_TYPE_SENT
        + " AND " + Sms.STATUS + " = "+ Sms.STATUS_COMPLETE +")";

    private static final String NEW_INCOMING_MM_CONSTRAINT =
            "(" + Mms.MESSAGE_BOX + "=" + Mms.MESSAGE_BOX_INBOX
            + " AND " + Mms.SEEN + "=0"
            + " AND (" + Mms.MESSAGE_TYPE + "=" + MESSAGE_TYPE_NOTIFICATION_IND
            + " OR " + Mms.MESSAGE_TYPE + "=" + MESSAGE_TYPE_RETRIEVE_CONF + "))";

    private static final MmsSmsNotificationInfoComparator INFO_COMPARATOR =
            new MmsSmsNotificationInfoComparator();

    private static final Uri UNDELIVERED_URI = Uri.parse("content://mms-sms/undelivered");


    private final static String NOTIFICATION_DELETED_ACTION =
            "com.android.mms.NOTIFICATION_DELETED_ACTION";

    public static class OnDeletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                Log.d(TAG, "[MessagingNotification] clear notification: mark all msgs seen");
            }

            Conversation.markAllConversationsAsSeen(context);
        }
    }

    public static final long THREAD_ALL = -1;
    public static final long THREAD_NONE = -2;
    /**
     * Keeps track of the thread ID of the conversation that's currently displayed to the user
     */
    private static long sCurrentlyDisplayedThreadId;
    private static final Object sCurrentlyDisplayedThreadLock = new Object();

    private static OnDeletedReceiver sNotificationDeletedReceiver = new OnDeletedReceiver();
    private static Intent sNotificationOnDeleteIntent;
    private static Handler mToastHandler = new Handler();

    private MessagingNotification() {
    }

    public static void init(Context context) {
        // set up the intent filter for notification deleted action
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NOTIFICATION_DELETED_ACTION);
        context.registerReceiver(sNotificationDeletedReceiver, intentFilter);

        // initialize the notification deleted action
        sNotificationOnDeleteIntent = new Intent(NOTIFICATION_DELETED_ACTION);
    }

    /**
     * Specifies which message thread is currently being viewed by the user. New messages in that
     * thread will not generate a notification icon and will play the notification sound at a lower
     * volume. Make sure you set this to THREAD_NONE when the UI component that shows the thread is
     * no longer visible to the user (e.g. Activity.onPause(), etc.)
     * @param threadId The ID of the thread that the user is currently viewing. Pass THREAD_NONE
     *  if the user is not viewing a thread, or THREAD_ALL if the user is viewing the conversation
     *  list (note: that latter one has no effect as of this implementation)
     */
    public static void setCurrentlyDisplayedThreadId(long threadId) {
        synchronized (sCurrentlyDisplayedThreadLock) {
            sCurrentlyDisplayedThreadId = threadId;
        }
    }

    /**
     * Checks to see if there are any "unseen" messages or delivery
     * reports.  Shows the most recent notification if there is one.
     * Does its work and query in a worker thread.
     *
     * @param context the context to use
     */
    public static void nonBlockingUpdateNewMessageIndicator(final Context context,
            final long newMsgThreadId,
            final boolean isStatusMessage) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                blockingUpdateNewMessageIndicator(context, newMsgThreadId, isStatusMessage);
            }
        }, "MessagingNotification.nonBlockingUpdateNewMessageIndicator").start();
    }

    /**
     * Checks to see if there are any "unseen" messages or delivery
     * reports.  Shows the most recent notification if there is one.
     *
     * @param context the context to use
     * @param newMsgThreadId The thread ID of a new message that we're to notify about; if there's
     *  no new message, use THREAD_NONE. If we should notify about multiple or unknown thread IDs,
     *  use THREAD_ALL.
     * @param isStatusMessage
     */
    public static void blockingUpdateNewMessageIndicator(Context context, long newMsgThreadId,
            boolean isStatusMessage) {
        synchronized (sCurrentlyDisplayedThreadLock) {
            if (newMsgThreadId > 0 && newMsgThreadId == sCurrentlyDisplayedThreadId) {
                playInConversationNotificationSound(context);
                return;
            }
        }

        SortedSet<MmsSmsNotificationInfo> accumulator =
                new TreeSet<MmsSmsNotificationInfo>(INFO_COMPARATOR);
        MmsSmsDeliveryInfo delivery = null;
        Set<Long> threads = new HashSet<Long>(4);

        int count = 0;
        count += accumulateNotificationInfo(
                accumulator, getMmsNewMessageNotificationInfo(context, threads));
        count += accumulateNotificationInfo(
                accumulator, getSmsNewMessageNotificationInfo(context, threads));

        cancelNotification(context, NOTIFICATION_ID);
        if (!accumulator.isEmpty()) {
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                Log.d(TAG, "blockingUpdateNewMessageIndicator: count=" + count +
                        ", newMsgThreadId=" + newMsgThreadId);
            }
            accumulator.first().deliver(context, newMsgThreadId != THREAD_NONE, count, threads.size());
        }

        // And deals with delivery reports (which use Toasts). It's safe to call in a worker
        // thread because the toast will eventually get posted to a handler.
        delivery = getSmsNewDeliveryInfo(context);
        if (delivery != null) {
            delivery.deliver(context, isStatusMessage);
        }
    }

    /**
     * Play the in-conversation notification sound (it's the regular notification sound, but
     * played at half-volume
     */
    private static void playInConversationNotificationSound(Context context) {
        final AudioManager audioManager =
            (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String ringtoneStr = sp.getString(MessagingPreferenceActivity.NOTIFICATION_RINGTONE,
                null);
        if (TextUtils.isEmpty(ringtoneStr)) {
            // Nothing to play
            return;
        }
        Uri ringtoneUri = Uri.parse(ringtoneStr);
        NotificationPlayer player = new NotificationPlayer(LogTag.APP);
        player.play(context, ringtoneUri, false, AudioManager.STREAM_NOTIFICATION,
                IN_CONVERSATION_NOTIFICATION_VOLUME);
    }

    /**
     * Updates all pending notifications, clearing or updating them as
     * necessary.
     */
    public static void blockingUpdateAllNotifications(final Context context) {
        nonBlockingUpdateNewMessageIndicator(context, THREAD_NONE, false);
        updateSendFailedNotification(context);
        updateDownloadFailedNotification(context);
    }

    private static final int accumulateNotificationInfo(
            SortedSet<MmsSmsNotificationInfo> set, MmsSmsNotificationInfo info) {
        if (info != null) {
            set.add(info);

            return info.mCount;
        }

        return 0;
    }

    private static final class MmsSmsDeliveryInfo {
        public CharSequence mTicker;
        public long mTimeMillis;

        public MmsSmsDeliveryInfo(CharSequence ticker, long timeMillis) {
            mTicker = ticker;
            mTimeMillis = timeMillis;
        }

        public void deliver(Context context, boolean isStatusMessage) {
            updateDeliveryNotification(
                    context, isStatusMessage, mTicker, mTimeMillis);
        }
    }

    private static final class MmsSmsNotificationInfo {
        public final Intent mClickIntent;
        public final String mDescription;
        public final int mIconResourceId;
        public final CharSequence mTicker;
        public final long mTimeMillis;
        public final String mTitle;
        public final int mCount;

        public MmsSmsNotificationInfo(
                Intent clickIntent, String description, int iconResourceId,
                CharSequence ticker, long timeMillis, String title, int count) {
            mClickIntent = clickIntent;
            mDescription = description;
            mIconResourceId = iconResourceId;
            mTicker = ticker;
            mTimeMillis = timeMillis;
            mTitle = title;
            mCount = count;
        }

        public void deliver(Context context, boolean isNew, int count, int uniqueThreads) {
            updateNotification(
                    context, mClickIntent, mDescription, mIconResourceId, isNew,
                    (isNew? mTicker : null), // only display the ticker if the message is new
                    mTimeMillis, mTitle, count, uniqueThreads);
        }

        public long getTime() {
            return mTimeMillis;
        }
    }

    private static final class MmsSmsNotificationInfoComparator
            implements Comparator<MmsSmsNotificationInfo> {
        @Override
        public int compare(
                MmsSmsNotificationInfo info1, MmsSmsNotificationInfo info2) {
            return Long.signum(info2.getTime() - info1.getTime());
        }
    }

    private static final MmsSmsNotificationInfo getMmsNewMessageNotificationInfo(
            Context context, Set<Long> threads) {
        ContentResolver resolver = context.getContentResolver();

        // This query looks like this when logged:
        // I/Database(  147): elapsedTime4Sql|/data/data/com.android.providers.telephony/databases/
        // mmssms.db|0.362 ms|SELECT thread_id, date, _id, sub, sub_cs FROM pdu WHERE ((msg_box=1
        // AND seen=0 AND (m_type=130 OR m_type=132))) ORDER BY date desc

        Cursor cursor = SqliteWrapper.query(context, resolver, Mms.CONTENT_URI,
                            MMS_STATUS_PROJECTION, NEW_INCOMING_MM_CONSTRAINT,
                            null, Mms.DATE + " desc");

        if (cursor == null) {
            return null;
        }

        try {
            if (!cursor.moveToFirst()) {
                return null;
            }

            long msgId = cursor.getLong(COLUMN_MMS_ID);
            Uri msgUri = Mms.CONTENT_URI.buildUpon().appendPath(
                    Long.toString(msgId)).build();
            String address = AddressUtils.getFrom(context, msgUri);

            Contact contact = Contact.get(address, false);
            if (contact.getSendToVoicemail()) {
                // don't notify
                return null;
            }

            String subject = getMmsSubject(
                    cursor.getString(COLUMN_SUBJECT), cursor.getInt(COLUMN_SUBJECT_CS));
            long threadId = cursor.getLong(COLUMN_THREAD_ID);
            long timeMillis = cursor.getLong(COLUMN_DATE) * 1000;

            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                Log.d(TAG, "getMmsNewMessageNotificationInfo: count=" + cursor.getCount() +
                        ", first addr = " + address + ", thread_id=" + threadId);
            }

            MmsSmsNotificationInfo info = getNewMessageNotificationInfo(
                    address, subject, context,
                    R.drawable.stat_notify_mms, null, threadId,
                    timeMillis, cursor.getCount());

            threads.add(threadId);
            while (cursor.moveToNext()) {
                threads.add(cursor.getLong(COLUMN_THREAD_ID));
            }

            return info;
        } finally {
            cursor.close();
        }
    }

    private static final MmsSmsDeliveryInfo getSmsNewDeliveryInfo(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = SqliteWrapper.query(context, resolver, Sms.CONTENT_URI,
                    SMS_STATUS_PROJECTION, NEW_DELIVERY_SM_CONSTRAINT,
                    null, Sms.DATE);

        if (cursor == null) {
            return null;
        }

        try {
            if (!cursor.moveToLast()) {
                return null;
            }

            String address = cursor.getString(COLUMN_SMS_ADDRESS);
            long timeMillis = 3000;

            Contact contact = Contact.get(address, false);
            String name = contact.getNameAndNumber();

            return new MmsSmsDeliveryInfo(context.getString(R.string.delivery_toast_body, name),
                timeMillis);

        } finally {
            cursor.close();
        }
    }

    private static final MmsSmsNotificationInfo getSmsNewMessageNotificationInfo(
            Context context, Set<Long> threads) {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = SqliteWrapper.query(context, resolver, Sms.CONTENT_URI,
                            SMS_STATUS_PROJECTION, NEW_INCOMING_SM_CONSTRAINT,
                            null, Sms.DATE + " desc");

        if (cursor == null) {
            return null;
        }

        try {
            if (!cursor.moveToFirst()) {
                return null;
            }

            String address = cursor.getString(COLUMN_SMS_ADDRESS);

            Contact contact = Contact.get(address, false);
            if (contact.getSendToVoicemail()) {
                // don't notify
                return null;
            }

            String body = cursor.getString(COLUMN_SMS_BODY);
            long threadId = cursor.getLong(COLUMN_THREAD_ID);
            long timeMillis = cursor.getLong(COLUMN_DATE);

            if (Log.isLoggable(LogTag.APP, Log.VERBOSE))
            {
                Log.d(TAG, "getSmsNewMessageNotificationInfo: count=" + cursor.getCount() +
                        ", first addr=" + address + ", thread_id=" + threadId);
            }

            MmsSmsNotificationInfo info = getNewMessageNotificationInfo(
                    address, body, context, R.drawable.stat_notify_sms,
                    null, threadId, timeMillis, cursor.getCount());

            threads.add(threadId);
            while (cursor.moveToNext()) {
                threads.add(cursor.getLong(COLUMN_THREAD_ID));
            }

            return info;
        } finally {
            cursor.close();
        }
    }

    private static final MmsSmsNotificationInfo getNewMessageNotificationInfo(
            String address,
            String body,
            Context context,
            int iconResourceId,
            String subject,
            long threadId,
            long timeMillis,
            int count) {
        Intent clickIntent = ComposeMessageActivity.createIntent(context, threadId);
        clickIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        String senderInfo = buildTickerMessage(
                context, address, null, null).toString();
        String senderInfoName = senderInfo.substring(
                0, senderInfo.length() - 2);
        CharSequence ticker = buildTickerMessage(
                context, address, subject, body);

        return new MmsSmsNotificationInfo(
                clickIntent, body, iconResourceId, ticker, timeMillis,
                senderInfoName, count);
    }

    public static void cancelNotification(Context context, int notificationId) {
        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);

        nm.cancel(notificationId);
    }

    private static void updateDeliveryNotification(final Context context,
                                                   boolean isStatusMessage,
                                                   final CharSequence message,
                                                   final long timeMillis) {
        if (!isStatusMessage) {
            return;
        }


        if (!MessagingPreferenceActivity.getNotificationEnabled(context)) {
            return;
        }

        mToastHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, (int)timeMillis).show();
            }
        });
    }

    private static void updateNotification(
            Context context,
            Intent clickIntent,
            String description,
            int iconRes,
            boolean isNew,
            CharSequence ticker,
            long timeMillis,
            String title,
            int messageCount,
            int uniqueThreadCount) {
        if (!MessagingPreferenceActivity.getNotificationEnabled(context)) {
            return;
        }

        Notification notification = new Notification(iconRes, ticker, timeMillis);
        Intent[] intents;

        // If we have more than one unique thread, change the title (which would
        // normally be the contact who sent the message) to a generic one that
        // makes sense for multiple senders, and change the Intent to take the
        // user to the conversation list instead of the specific thread.
        if (uniqueThreadCount > 1) {
            title = context.getString(R.string.notification_multiple_title);
            Intent mainActivityIntent = new Intent(Intent.ACTION_MAIN);

            mainActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            mainActivityIntent.setType("vnd.android-dir/mms-sms");
            intents = new Intent[1];
            intents[0] = mainActivityIntent;
        } else {
            // Build a stack of intents so when the user hits back from the ComposeMessageActivity
            // we're sending them to, they'll up on ConversationList.
            intents = new Intent[2];

            // First: root activity of the MessagingApp: ConversationList.
            // This is a convenient way to make the proper Intent to launch and
            // reset an application's task.
            intents[0] = Intent.makeRestartActivityTask(new ComponentName(context,
                    ConversationList.class));
            intents[1] = clickIntent;
        }

        // If there is more than one message, change the description (which
        // would normally be a snippet of the individual message text) to
        // a string indicating how many "unseen" messages there are.
        if (messageCount > 1) {
            description = context.getString(R.string.notification_multiple,
                    Integer.toString(messageCount));
        }

        // Make a startActivity() PendingIntent for the notification.
        PendingIntent pendingIntent = PendingIntent.getActivities(context, 0, intents,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // Update the notification.
        notification.setLatestEventInfo(context, title, description, pendingIntent);

        if (isNew) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String vibrateWhen;
            if (sp.contains(MessagingPreferenceActivity.NOTIFICATION_VIBRATE_WHEN)) {
                vibrateWhen =
                    sp.getString(MessagingPreferenceActivity.NOTIFICATION_VIBRATE_WHEN, null);
            } else if (sp.contains(MessagingPreferenceActivity.NOTIFICATION_VIBRATE)) {
                vibrateWhen = sp.getBoolean(MessagingPreferenceActivity.NOTIFICATION_VIBRATE, false) ?
                    context.getString(R.string.prefDefault_vibrate_true) :
                    context.getString(R.string.prefDefault_vibrate_false);
            } else {
                vibrateWhen = context.getString(R.string.prefDefault_vibrateWhen);
            }

            boolean vibrateAlways = vibrateWhen.equals("always");
            boolean vibrateSilent = vibrateWhen.equals("silent");
            AudioManager audioManager =
                (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            boolean nowSilent =
                audioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;

            if (vibrateAlways || vibrateSilent && nowSilent) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }

            String ringtoneStr = sp.getString(MessagingPreferenceActivity.NOTIFICATION_RINGTONE,
                    null);
            notification.sound = TextUtils.isEmpty(ringtoneStr) ? null : Uri.parse(ringtoneStr);
        }

        notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        notification.defaults |= Notification.DEFAULT_LIGHTS;

        // set up delete intent
        notification.deleteIntent = PendingIntent.getBroadcast(context, 0,
                sNotificationOnDeleteIntent, 0);

        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        nm.notify(NOTIFICATION_ID, notification);
    }

    protected static CharSequence buildTickerMessage(
            Context context, String address, String subject, String body) {
        String displayAddress = Contact.get(address, true).getName();

        StringBuilder buf = new StringBuilder(
                displayAddress == null
                ? ""
                : displayAddress.replace('\n', ' ').replace('\r', ' '));
        buf.append(':').append(' ');

        int offset = buf.length();
        if (!TextUtils.isEmpty(subject)) {
            subject = subject.replace('\n', ' ').replace('\r', ' ');
            buf.append(subject);
            buf.append(' ');
        }

        if (!TextUtils.isEmpty(body)) {
            body = body.replace('\n', ' ').replace('\r', ' ');
            buf.append(body);
        }

        SpannableString spanText = new SpannableString(buf.toString());
        spanText.setSpan(new StyleSpan(Typeface.BOLD), 0, offset,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        return spanText;
    }

    private static String getMmsSubject(String sub, int charset) {
        return TextUtils.isEmpty(sub) ? ""
                : new EncodedStringValue(charset, PduPersister.getBytes(sub)).getString();
    }

    public static void notifyDownloadFailed(Context context, long threadId) {
        notifyFailed(context, true, threadId, false);
    }

    public static void notifySendFailed(Context context) {
        notifyFailed(context, false, 0, false);
    }

    public static void notifySendFailed(Context context, boolean noisy) {
        notifyFailed(context, false, 0, noisy);
    }

    private static void notifyFailed(Context context, boolean isDownload, long threadId,
                                     boolean noisy) {
        // TODO factor out common code for creating notifications
        boolean enabled = MessagingPreferenceActivity.getNotificationEnabled(context);
        if (!enabled) {
            return;
        }

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Strategy:
        // a. If there is a single failure notification, tapping on the notification goes
        //    to the compose view.
        // b. If there are two failure it stays in the thread view. Selecting one undelivered
        //    thread will dismiss one undelivered notification but will still display the
        //    notification.If you select the 2nd undelivered one it will dismiss the notification.

        long[] msgThreadId = {0, 1};    // Dummy initial values, just to initialize the memory
        int totalFailedCount = getUndeliveredMessageCount(context, msgThreadId);
        if (totalFailedCount == 0 && !isDownload) {
            return;
        }
        // The getUndeliveredMessageCount method puts a non-zero value in msgThreadId[1] if all
        // failures are from the same thread.
        // If isDownload is true, we're dealing with 1 specific failure; therefore "all failed" are
        // indeed in the same thread since there's only 1.
        boolean allFailedInSameThread = (msgThreadId[1] != 0) || isDownload;

        Intent failedIntent;
        Notification notification = new Notification();
        String title;
        String description;
        if (totalFailedCount > 1) {
            description = context.getString(R.string.notification_failed_multiple,
                    Integer.toString(totalFailedCount));
            title = context.getString(R.string.notification_failed_multiple_title);
        } else {
            title = isDownload ?
                        context.getString(R.string.message_download_failed_title) :
                        context.getString(R.string.message_send_failed_title);

            description = context.getString(R.string.message_failed_body);
        }

        if (allFailedInSameThread) {
            failedIntent = new Intent(context, ComposeMessageActivity.class);
            if (isDownload) {
                // When isDownload is true, the valid threadId is passed into this function.
                failedIntent.putExtra("failed_download_flag", true);
            } else {
                threadId = msgThreadId[0];
                failedIntent.putExtra("undelivered_flag", true);
            }
            failedIntent.putExtra("thread_id", threadId);
        } else {
            failedIntent = new Intent(context, ConversationList.class);
        }

        failedIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, failedIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        notification.icon = R.drawable.stat_notify_sms_failed;

        notification.tickerText = title;

        notification.setLatestEventInfo(context, title, description, pendingIntent);

        if (noisy) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            boolean vibrate = sp.getBoolean(MessagingPreferenceActivity.NOTIFICATION_VIBRATE,
                    false /* don't vibrate by default */);
            if (vibrate) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }

            String ringtoneStr = sp.getString(MessagingPreferenceActivity.NOTIFICATION_RINGTONE,
                    null);
            notification.sound = TextUtils.isEmpty(ringtoneStr) ? null : Uri.parse(ringtoneStr);
        }

        if (isDownload) {
            nm.notify(DOWNLOAD_FAILED_NOTIFICATION_ID, notification);
        } else {
            nm.notify(MESSAGE_FAILED_NOTIFICATION_ID, notification);
        }
    }

    /**
     * Query the DB and return the number of undelivered messages (total for both SMS and MMS)
     * @param context The context
     * @param threadIdResult A container to put the result in, according to the following rules:
     *  threadIdResult[0] contains the thread id of the first message.
     *  threadIdResult[1] is nonzero if the thread ids of all the messages are the same.
     *  You can pass in null for threadIdResult.
     *  You can pass in a threadIdResult of size 1 to avoid the comparison of each thread id.
     */
    private static int getUndeliveredMessageCount(Context context, long[] threadIdResult) {
        Cursor undeliveredCursor = SqliteWrapper.query(context, context.getContentResolver(),
                UNDELIVERED_URI, MMS_THREAD_ID_PROJECTION, "read=0", null, null);
        if (undeliveredCursor == null) {
            return 0;
        }
        int count = undeliveredCursor.getCount();
        try {
            if (threadIdResult != null && undeliveredCursor.moveToFirst()) {
                threadIdResult[0] = undeliveredCursor.getLong(0);

                if (threadIdResult.length >= 2) {
                    // Test to see if all the undelivered messages belong to the same thread.
                    long firstId = threadIdResult[0];
                    while (undeliveredCursor.moveToNext()) {
                        if (undeliveredCursor.getLong(0) != firstId) {
                            firstId = 0;
                            break;
                        }
                    }
                    threadIdResult[1] = firstId;    // non-zero if all ids are the same
                }
            }
        } finally {
            undeliveredCursor.close();
        }
        return count;
    }

    public static void updateSendFailedNotification(Context context) {
        if (getUndeliveredMessageCount(context, null) < 1) {
            cancelNotification(context, MESSAGE_FAILED_NOTIFICATION_ID);
        } else {
            notifySendFailed(context);      // rebuild and adjust the message count if necessary.
        }
    }

    /**
     *  If all the undelivered messages belong to "threadId", cancel the notification.
     */
    public static void updateSendFailedNotificationForThread(Context context, long threadId) {
        long[] msgThreadId = {0, 0};
        if (getUndeliveredMessageCount(context, msgThreadId) > 0
                && msgThreadId[0] == threadId
                && msgThreadId[1] != 0) {
            cancelNotification(context, MESSAGE_FAILED_NOTIFICATION_ID);
        }
    }

    private static int getDownloadFailedMessageCount(Context context) {
        // Look for any messages in the MMS Inbox that are of the type
        // NOTIFICATION_IND (i.e. not already downloaded) and in the
        // permanent failure state.  If there are none, cancel any
        // failed download notification.
        Cursor c = SqliteWrapper.query(context, context.getContentResolver(),
                Mms.Inbox.CONTENT_URI, null,
                Mms.MESSAGE_TYPE + "=" +
                    String.valueOf(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) +
                " AND " + Mms.STATUS + "=" +
                    String.valueOf(DownloadManager.STATE_PERMANENT_FAILURE),
                null, null);
        if (c == null) {
            return 0;
        }
        int count = c.getCount();
        c.close();
        return count;
    }

    public static void updateDownloadFailedNotification(Context context) {
        if (getDownloadFailedMessageCount(context) < 1) {
            cancelNotification(context, DOWNLOAD_FAILED_NOTIFICATION_ID);
        }
    }

    public static boolean isFailedToDeliver(Intent intent) {
        return (intent != null) && intent.getBooleanExtra("undelivered_flag", false);
    }

    public static boolean isFailedToDownload(Intent intent) {
        return (intent != null) && intent.getBooleanExtra("failed_download_flag", false);
    }

    /**
     * Get the thread ID of the SMS message with the given URI
     * @param context The context
     * @param uri The URI of the SMS message
     * @return The thread ID, or THREAD_NONE if the URI contains no entries
     */
    public static long getSmsThreadId(Context context, Uri uri) {
        Cursor cursor = SqliteWrapper.query(
            context,
            context.getContentResolver(),
            uri,
            SMS_THREAD_ID_PROJECTION,
            null,
            null,
            null);

        if (cursor == null) {
            return THREAD_NONE;
        }

        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndex(Sms.THREAD_ID));
            } else {
                return THREAD_NONE;
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Get the thread ID of the MMS message with the given URI
     * @param context The context
     * @param uri The URI of the SMS message
     * @return The thread ID, or THREAD_NONE if the URI contains no entries
     */
    public static long getThreadId(Context context, Uri uri) {
        Cursor cursor = SqliteWrapper.query(
                context,
                context.getContentResolver(),
                uri,
                MMS_THREAD_ID_PROJECTION,
                null,
                null,
                null);

        if (cursor == null) {
            return THREAD_NONE;
        }

        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndex(Mms.THREAD_ID));
            } else {
                return THREAD_NONE;
            }
        } finally {
            cursor.close();
        }
    }
}
