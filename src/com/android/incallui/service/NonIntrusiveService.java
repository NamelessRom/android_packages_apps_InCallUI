/*
* <!--
*    Copyright (C) 2014 The NamelessROM Project
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>.
* -->
*/

package com.android.incallui.service;

import android.animation.ObjectAnimator;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.incallui.AnimationUtils;
import com.android.incallui.CallCommandClient;
import com.android.incallui.CallList;
import com.android.incallui.CallerInfoUtils;
import com.android.incallui.ContactInfoCache;
import com.android.incallui.InCallApp;
import com.android.incallui.InCallPresenter;
import com.android.incallui.Log;
import com.android.incallui.R;
import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.CallIdentification;

/**
 * A service, which attaches the non intrusive ui to the window manager as system alert
 */
public class NonIntrusiveService extends Service {
    public static final String ACTION_START = "action_start";

    private static final int SLIDE_IN_DURATION_MS = 1000;

    private static int MAX_HEIGHT = 0;

    private LinearLayout mRootView;
    private TextView     mNameTextView;
    private ImageView    mContactImage;

    private Call mCall;

    @Override public IBinder onBind(final Intent intent) { return null; }

    @Override public void onDestroy() {
        super.onDestroy();
        tearDown();
    }

    @Override public int onStartCommand(final Intent intent, final int flags, final int startId) {
        logDebug("onStartCommand called");

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();

        if (action != null && !action.isEmpty()) {
            logDebug("Action: " + action);
            if (action.equals(ACTION_START)) {
                showNonIntrusive();
            }
        } else {
            logDebug("Action is NULL or EMPTY!");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /**
     * Cleanup when the service gets destroyed
     */
    private void tearDown() {
        if (mRootView != null) {
            // Get hold of our window manager and remove the view
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.removeView(mRootView);
            mRootView = null;
        }
    }

    /**
     * Gets the whole thing rollin'
     */
    private void showNonIntrusive() {
        // Pass our service to InCallPresenter
        InCallPresenter.getInstance().setNonIntrusiveService(this);

        // Get hold of the window manager ...
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // ... and get the maximum display size
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(displayMetrics);
        MAX_HEIGHT = displayMetrics.heightPixels;

        // get hold of the inflater
        final LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);

        // Inflate our view
        mRootView = (LinearLayout) inflater.inflate(R.layout.call_card_incoming, null, false);

        // get the height of our card
        final int cHeight = getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height);

        // Setup layout parameters, use TYPE_STATUS_BAR_PANEL as TYPE_SYSTEM_OVERLAY can not
        // receive touch input anymore as of android 4.x
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = cHeight;

        // add everything as overlay ...
        wm.addView(mRootView, params);

        // ... bind actions to our views ...
        bindActions(mRootView);

        // ... and slide it in, wooohhoo
        ObjectAnimator outAnim = ObjectAnimator.ofFloat(mRootView, "y", 0, -cHeight);
        outAnim.setDuration(0).start();

        outAnim = ObjectAnimator.ofFloat(mRootView, "y", cHeight, 0);
        outAnim.setDuration(SLIDE_IN_DURATION_MS).start();

        getCallInformation();
    }

    /**
     * Sets up our views and actions
     *
     * @param rootView
     */
    private void bindActions(final LinearLayout rootView) {
        // Setup the fields to show the information of the call
        mNameTextView = (TextView) rootView.findViewById(R.id.txt_contact_name);
        mContactImage = (ImageView) rootView.findViewById(R.id.img_contact);

        // Setup the call button
        final Button answer = (Button) rootView.findViewById(R.id.btn_answer);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                if (mCall != null) {
                    InCallPresenter.getInstance().startIncomingCallUi();
                    CallCommandClient.getInstance().answerCall(mCall.getCallId());
                }
                stopSelf();
            }
        });

        // Setup the reject button
        final Button reject = (Button) rootView.findViewById(R.id.btn_reject);
        reject.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                if (mCall != null) CallCommandClient.getInstance().rejectCall(mCall, false, null);
                stopSelf();
            }
        });

        rootView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (mRootView != view) return false;
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_MOVE:
                        final float y = event.getY();
                        if (y < (MAX_HEIGHT - 10) && y > 0) {
                            view.setY(y);
                        }
                        view.invalidate();
                        break;
                }
                return true;
            }
        });
    }

    /**
     * Get information about the caller
     */
    private void getCallInformation() {
        // Get the incoming call
        mCall = InCallPresenter.getInstance().getIncomingCall();
        logDebug(String.format("1) mCall != null -> %s", (mCall != null)));

        // if the incoming call is null, try to fetch it via another way
        if (mCall == null) { mCall = CallList.getInstance().getIncomingCall(); }
        logDebug(String.format("2) mCall != null -> %s", (mCall != null)));

        // Lookup contact info
        startContactInfoSearch(((mCall != null) ? mCall.getIdentification() : null));
    }

    /**
     * Starts a query for more contact data for the save primary and secondary calls
     */
    private void startContactInfoSearch(final CallIdentification identification) {
        if (identification == null) return;
        final ContactInfoCache cache = ContactInfoCache.getInstance(NonIntrusiveService.this);

        cache.findInfo(identification, true, new ContactInfoCache.ContactInfoCacheCallback() {
            @Override public void onContactInfoComplete(final int callId,
                    final ContactInfoCache.ContactCacheEntry entry) {
                mNameTextView.setText(entry.name == null ? entry.number : entry.name);
                if (entry.personUri != null) {
                    CallerInfoUtils.sendViewNotification(NonIntrusiveService.this, entry.personUri);
                }
            }

            @Override public void onImageLoadComplete(final int callId,
                    final ContactInfoCache.ContactCacheEntry entry) {
                if (entry.photo != null) {
                    AnimationUtils.startCrossFade(
                            mContactImage, mContactImage.getDrawable(), entry.photo);
                }
            }
        });
    }

    /**
     * Logs if debug is enabled
     *
     * @param msg The message to log
     */
    private void logDebug(final String msg) {
        if (InCallApp.DEBUG) Log.d(this, msg);
    }

}
