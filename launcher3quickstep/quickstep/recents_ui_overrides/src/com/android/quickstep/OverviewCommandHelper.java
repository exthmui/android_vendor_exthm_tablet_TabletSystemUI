/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.android.launcher3.BaseDraggingActivity;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.ActivityManagerWrapper;

/**
 * Helper class to handle various atomic commands for switching between Overview.
 */
@TargetApi(Build.VERSION_CODES.P)
public class OverviewCommandHelper {

    private static final String TAG = "OverviewProxy";
    private final Context mContext;
    private final ActivityManagerWrapper mAM;
    private final RecentsModel mRecentsModel;
    private final OverviewComponentObserver mOverviewComponentObserver;

    private long mLastToggleTime;

    public OverviewCommandHelper(Context context, OverviewComponentObserver observer) {
        mContext = context;
        mAM = ActivityManagerWrapper.getInstance();
        mRecentsModel = RecentsModel.INSTANCE.get(mContext);
        mOverviewComponentObserver = observer;
    }

    public void onOverviewToggle() {
        Log.d(TAG, "onOverviewToggle");
        // If currently screen pinning, do not enter overview
        if (mAM.isScreenPinningActive()) {
            Log.d(TAG, "onOverviewToggle isScreenPinningActive");
            return;
        }

        mAM.closeSystemWindows(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
        MAIN_EXECUTOR.execute(new RecentsActivityCommand<>());
    }

    public void onOverviewShown(boolean triggeredFromAltTab) {
        MAIN_EXECUTOR.execute(new ShowRecentsCommand(triggeredFromAltTab));
    }

    public void onOverviewHidden() {
        MAIN_EXECUTOR.execute(new HideRecentsCommand());
    }

    private class ShowRecentsCommand extends RecentsActivityCommand {

        private final boolean mTriggeredFromAltTab;

        ShowRecentsCommand(boolean triggeredFromAltTab) {
            mTriggeredFromAltTab = triggeredFromAltTab;
        }

        @Override
        protected void onTransitionComplete() {
            // TODO(b/138729100) This doesn't execute first time launcher is run
            if (mTriggeredFromAltTab) {
                RecentsView rv = (RecentsView) mHelper.getVisibleRecentsView();
                if (rv == null) {
                    return;
                }

                // Ensure that recents view has focus so that it receives the followup key inputs
                TaskView taskView = rv.getNextTaskView();
                if (taskView == null) {
                    if (rv.getTaskViewCount() > 0) {
                        taskView = rv.getTaskViewAt(0);
                        taskView.requestFocus();
                    } else {
                        rv.requestFocus();
                    }
                } else {
                    taskView.requestFocus();
                }
            }
        }
    }

    private class HideRecentsCommand extends RecentsActivityCommand {

    }

    private class RecentsActivityCommand<T extends BaseDraggingActivity> implements Runnable {

        protected final ActivityControlHelper<T> mHelper;
        private final long mCreateTime;

        public RecentsActivityCommand() {
            mHelper = mOverviewComponentObserver.getActivityControlHelper();
            mCreateTime = SystemClock.elapsedRealtime();

            // Preload the plan
            mRecentsModel.getTasks(null);
        }

        @Override
        public void run() {
            Log.d(TAG, "RecentsActivityCommand starts to run");
            mLastToggleTime = mCreateTime;

            if (mHelper.switchToRecentsIfVisible(this::onTransitionComplete)) {
                Log.d(TAG, "RecentsActivityCommand command switchToRecents succeed");
                // If successfully switched, then return
                return;
            }
            // Otherwise, start overview.
            Log.d(TAG, "RecentsActivityCommand command start overview " + mHelper);
            mOverviewComponentObserver.startOverview();
        }

        protected void onTransitionComplete() { }
    }
}
