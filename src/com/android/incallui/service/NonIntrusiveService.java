package com.android.incallui.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
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
 * Created by alex on 19.06.14.
 */
public class NonIntrusiveService extends Service {
    public static final String ACTION_START = "action_start";

    private static final int SLIDE_IN_DURATION_MS = 500;

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

    private void showNonIntrusive() {
        // Pass our service to InCallPresenter
        InCallPresenter.getInstance().setNonIntrusiveService(this);

        // Get hold of the window manager ...
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // ... and of the inflater
        final LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);

        // Inflate our view
        mRootView = (LinearLayout) inflater.inflate(R.layout.call_card_incoming, null, false);

        // Setup layout parameters
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height);

        // Bind actions to our views
        bindActions(mRootView);

        // And add everything as overlay, wohoo
        wm.addView(mRootView, params);
    }

    /**
     * Sets up our views and actions
     *
     * @param rootView
     */
    private void bindActions(final LinearLayout rootView) {
        // Get the incoming call
        mCall = InCallPresenter.getInstance().getIncomingCall();
        logDebug(String.format("1) mCall != null -> %s", (mCall != null)));

        // if the incoming call is null, try to fetch it via another way
        if (mCall == null) { mCall = CallList.getInstance().getIncomingCall(); }
        logDebug(String.format("2) mCall != null -> %s", (mCall != null)));

        final CallIdentification ident = ((mCall != null) ? mCall.getIdentification() : null);

        // Setup the fields to show the information of the call
        mNameTextView = (TextView) rootView.findViewById(R.id.txt_contact_name);
        mContactImage = (ImageView) rootView.findViewById(R.id.img_contact);

        // Setup the call button
        final Button answer = (Button) rootView.findViewById(R.id.btn_answer);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                InCallPresenter.getInstance().startIncomingCallUi();
                CallCommandClient.getInstance().answerCall(mCall.getCallId());
                stopSelf();
            }
        });

        // Setup the reject button
        final Button reject = (Button) rootView.findViewById(R.id.btn_reject);
        reject.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                CallCommandClient.getInstance().rejectCall(mCall, false, null);
                stopSelf();
            }
        });

        // Slide in the dialog
        final LinearLayout vg = (LinearLayout) rootView.findViewById(R.id.root);

        // Some fancy animations
        vg.setTranslationY(getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height));
        vg.animate().translationY(0.0f).setDuration(SLIDE_IN_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).start();

        // Lookup contact info
        startContactInfoSearch(ident);
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
