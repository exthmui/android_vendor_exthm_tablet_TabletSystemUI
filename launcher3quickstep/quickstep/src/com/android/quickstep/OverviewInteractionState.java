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

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.android.launcher3.util.MainThreadInitializedObject;
import com.android.systemui.shared.recents.ISystemUiProxy;

/**
 * Sets alpha for the back button
 */
public class OverviewInteractionState {

    private static final String TAG = "OverviewFlags";

    // We do not need any synchronization for this variable as its only written on UI thread.
    public static final MainThreadInitializedObject<OverviewInteractionState> INSTANCE =
            new MainThreadInitializedObject<>(OverviewInteractionState::new);

    private static final int MSG_SET_PROXY = 200;
    private static final int MSG_SET_BACK_BUTTON_ALPHA = 201;

    private final Handler mBgHandler;

    // These are updated on the background thread
    private ISystemUiProxy mISystemUiProxy;

    private int mSystemUiStateFlags;

    private OverviewInteractionState(Context context) {

        // Data posted to the uihandler will be sent to the bghandler. Data is sent to uihandler
        // because of its high send frequency and data may be very different than the previous value
        // For example, send back alpha on uihandler to avoid flickering when setting its visibility
        mBgHandler = new Handler(UI_HELPER_EXECUTOR.getLooper(), this::handleBgMessage);
    }

    public void setSystemUiProxy(ISystemUiProxy proxy) {
        mBgHandler.obtainMessage(MSG_SET_PROXY, proxy).sendToTarget();
    }

    public void setSystemUiStateFlags(int stateFlags) {
        mSystemUiStateFlags = stateFlags;
    }

    public int getSystemUiStateFlags() {
        return mSystemUiStateFlags;
    }

    private boolean handleBgMessage(Message msg) {
        switch (msg.what) {
            case MSG_SET_PROXY:
                mISystemUiProxy = (ISystemUiProxy) msg.obj;
                break;
            case MSG_SET_BACK_BUTTON_ALPHA:
                applyBackButtonAlpha((float) msg.obj, msg.arg1 == 1);
                return true;
        }
        return true;
    }

    @WorkerThread
    private void applyBackButtonAlpha(float alpha, boolean animate) {
        if (mISystemUiProxy == null) {
            return;
        }
        try {
            mISystemUiProxy.setBackButtonAlpha(alpha, animate);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to update overview back button alpha", e);
        }
    }

}