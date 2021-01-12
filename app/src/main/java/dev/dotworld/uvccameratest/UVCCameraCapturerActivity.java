package dev.dotworld.uvccameratest;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.twilio.video.ConnectOptions;
import com.twilio.video.LocalAudioTrack;
import com.twilio.video.LocalParticipant;
import com.twilio.video.LocalVideoTrack;
import com.twilio.video.RemoteAudioTrack;
import com.twilio.video.RemoteAudioTrackPublication;
import com.twilio.video.RemoteDataTrack;
import com.twilio.video.RemoteDataTrackPublication;
import com.twilio.video.RemoteParticipant;
import com.twilio.video.RemoteVideoTrack;
import com.twilio.video.RemoteVideoTrackPublication;
import com.twilio.video.Room;
import com.twilio.video.TwilioException;
import com.twilio.video.Video;
import com.twilio.video.VideoTrack;
import com.twilio.video.VideoView;

import java.util.Collections;

import dev.dotworld.uvccameratest.Utils.UVCCameraCapturer;


/**
 * Simple activity that renders UVCCameraCapturer frames to a SurfaceView.
 */
public class UVCCameraCapturerActivity extends AppCompatActivity {

    private static final String TAG =  UVCCameraCapturerActivity.class.getCanonicalName();

    private UVCCameraCapturer  uvcCameraCapturer;
    private LocalVideoTrack localVideoTrack;
    private LocalAudioTrack localAudioTrack;

    private ImageButton disconnected;
    private static final String LOCAL_AUDIO_TRACK_NAME = "mic";
    AudioManager audioManager;
    private static final String LOCAL_VIDEO_TRACK_NAME = "usbcamera";

    String[] permissionsRequired = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE};

    private static final int CAMERA_MIC_PERMISSION_REQUEST_CODE = 1;

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            uvcCameraCapturer = new UVCCameraCapturer(surfaceHolder.getSurface());
//            uvcCameraCapturer = new UVCCameraCapturer(UVCCameraCapturerActivity.this, surfaceHolder.getSurface());
            localVideoTrack = LocalVideoTrack.create( UVCCameraCapturerActivity.this, true, uvcCameraCapturer, LOCAL_VIDEO_TRACK_NAME);

//            connectToRoom(getIntent().getStringExtra("roomId"), getIntent().getStringExtra("accessToken"));
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

        }
    };

    Room room;
    VideoView thumbnailVideoView;
    private LocalParticipant localParticipant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uvccamera_capturer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!checkPermissionForCameraAndMicrophone()) {
            requestPermissionForCameraAndMicrophone();
        } else {
            createAudioAndVideoTracks();

            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setSpeakerphoneOn(true);
        }

        thumbnailVideoView = findViewById(R.id.thumbnail_video_view);
        disconnected = findViewById(R.id.disconnect);

        disconnected.setOnClickListener(v -> {
            if (room != null) {
                room.disconnect();
            }
            finish();
        });
    }


    private boolean checkPermissionForCameraAndMicrophone() {
        int resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        int resultExternalStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int read_phone_state = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE);
        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED && resultExternalStorage == PackageManager.PERMISSION_GRANTED
                && read_phone_state == PackageManager.PERMISSION_GRANTED;
    }


    private void createAudioAndVideoTracks() {
        localAudioTrack = LocalAudioTrack.create(this, true, LOCAL_AUDIO_TRACK_NAME);

        SurfaceView uvcCameraVideoView = findViewById(R.id.uvccamera_video_view);
        uvcCameraVideoView.getHolder().addCallback(surfaceCallback);
    }

    private void connectToRoom(String roomName, String token) {
        if (localVideoTrack == null) {
            Log.i(TAG, "connectToRoom: Video track not available");
            // Sentry.captureMessage("Video track is not available.No joining the call");
            return;
        }

        ConnectOptions connectOptions = new ConnectOptions
                .Builder(token)
                .roomName(roomName)
                .videoTracks(Collections.singletonList(localVideoTrack))
                .enableAutomaticSubscription(true)
                .audioTracks(Collections.singletonList(localAudioTrack))
                .build();

        room = Video.connect( UVCCameraCapturerActivity.this, connectOptions, new Room.Listener() {
            @Override
            public void onConnected(@NonNull Room room) {
                try {
                    localParticipant = room.getLocalParticipant();
                    if (localParticipant != null) {
                        try {
                            localParticipant.publishTrack(localVideoTrack);
                        } catch (Exception e) {
                            // Sentry.captureException(e);
                            e.printStackTrace();
                        }
                    }
                    addRemoteParticipant(room);
                } catch (Exception e) {
                    e.printStackTrace();
                    // Sentry.captureException(e);
                }
            }

            @Override
            public void onConnectFailure(@NonNull Room room, @NonNull TwilioException twilioException) {
                if (twilioException.getCause() != null) {
                    // Sentry.captureException(twilioException.getCause());
                }
                Log.e(TAG, "onConnectFailure" + twilioException.toString());
            }

            @Override
            public void onReconnecting(@NonNull Room room, @NonNull TwilioException twilioException) {
                Log.e(TAG, "onReconnecting: " + twilioException.toString());
            }

            @Override
            public void onReconnected(@NonNull Room room) {
                Log.e(TAG, "onReconnected");
            }

            @Override
            public void onDisconnected(@NonNull Room room, @Nullable TwilioException twilioException) {
                Log.e(TAG, "onDisconnected");
                // Sentry.captureMessage("User disconnected from call");
                finish();
            }

            @Override
            public void onParticipantConnected(@NonNull Room room, @NonNull RemoteParticipant remoteParticipant) {
                if (remoteParticipant.getRemoteVideoTracks().size() > 0) {
                    RemoteVideoTrackPublication remoteVideoTrackPublication =
                            remoteParticipant.getRemoteVideoTracks().get(0);

                    /*
                     * Only render video tracks that are subscribed to
                     */
                    if (remoteVideoTrackPublication.isTrackSubscribed()) {
                        addRemoteParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
                    }
                }
                remoteParticipant.setListener(remoteParticipantListener());
            }

            @Override
            public void onParticipantDisconnected(@NonNull Room room, @NonNull RemoteParticipant remoteParticipant) {
                Log.e(TAG, "onParticipantDisconnected: ");
                // Sentry.captureMessage("Participant disconnected from call");
                finish();
            }

            @Override
            public void onRecordingStarted(@NonNull Room room) {

            }

            @Override
            public void onRecordingStopped(@NonNull Room room) {
                Log.e(TAG, "onRecordingStopped: ");

            }
        });

    }

    private void addRemoteParticipant(@NonNull Room room) {
        for (RemoteParticipant remoteParticipant : room.getRemoteParticipants()) {
            if (remoteParticipant.getRemoteVideoTracks().size() > 0) {
                RemoteVideoTrackPublication remoteVideoTrackPublication =
                        remoteParticipant.getRemoteVideoTracks().get(0);

                /*
                 * Only render video tracks that are subscribed to
                 */
                if (remoteVideoTrackPublication.isTrackSubscribed()) {
                    addRemoteParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
                }
            }
            remoteParticipant.setListener(remoteParticipantListener());

            break;
        }
    }

//    @Override
//    protected void onPause() {
//        super.onPause();
//        if (localVideoTrack != null) {
//            if (localParticipant != null) {
//                localParticipant.unpublishTrack(localVideoTrack);
//            }
//            localVideoTrack.release();
//        }
//        localVideoTrack = null;
//    }

    @Override
    protected void onDestroy() {
        if (room != null) {
            room.disconnect();
        }

        if (localVideoTrack != null) localVideoTrack.release();
        if (localAudioTrack != null) localAudioTrack.release();

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        if (localVideoTrack == null && uvcCameraCapturer != null && checkPermissionForCameraAndMicrophone()) {
            localVideoTrack = LocalVideoTrack.create( UVCCameraCapturerActivity.this,
                    true,
                    uvcCameraCapturer,
                    LOCAL_VIDEO_TRACK_NAME);

            if (localParticipant != null) {
                localParticipant.publishTrack(localVideoTrack);
            }
        }
        super.onResume();
    }

    private RemoteParticipant.Listener remoteParticipantListener() {
        return new RemoteParticipant.Listener() {
            @Override
            public void onAudioTrackPublished(RemoteParticipant remoteParticipant,
                                              RemoteAudioTrackPublication remoteAudioTrackPublication) {
            }

            @Override
            public void onAudioTrackUnpublished(RemoteParticipant remoteParticipant,
                                                RemoteAudioTrackPublication remoteAudioTrackPublication) {
            }

            @Override
            public void onDataTrackPublished(RemoteParticipant remoteParticipant,
                                             RemoteDataTrackPublication remoteDataTrackPublication) {
            }

            @Override
            public void onDataTrackUnpublished(RemoteParticipant remoteParticipant,
                                               RemoteDataTrackPublication remoteDataTrackPublication) {
            }

            @Override
            public void onVideoTrackPublished(RemoteParticipant remoteParticipant,
                                              RemoteVideoTrackPublication remoteVideoTrackPublication) {
            }

            @Override
            public void onVideoTrackUnpublished(RemoteParticipant remoteParticipant,
                                                RemoteVideoTrackPublication remoteVideoTrackPublication) {
            }

            @Override
            public void onAudioTrackSubscribed(RemoteParticipant remoteParticipant,
                                               RemoteAudioTrackPublication remoteAudioTrackPublication,
                                               RemoteAudioTrack remoteAudioTrack) {
            }

            @Override
            public void onAudioTrackUnsubscribed(RemoteParticipant remoteParticipant,
                                                 RemoteAudioTrackPublication remoteAudioTrackPublication,
                                                 RemoteAudioTrack remoteAudioTrack) {
            }

            @Override
            public void onAudioTrackSubscriptionFailed(RemoteParticipant remoteParticipant,
                                                       RemoteAudioTrackPublication remoteAudioTrackPublication,
                                                       TwilioException twilioException) {
            }

            @Override
            public void onDataTrackSubscribed(RemoteParticipant remoteParticipant,
                                              RemoteDataTrackPublication remoteDataTrackPublication,
                                              RemoteDataTrack remoteDataTrack) {

            }

            @Override
            public void onDataTrackUnsubscribed(RemoteParticipant remoteParticipant,
                                                RemoteDataTrackPublication remoteDataTrackPublication,
                                                RemoteDataTrack remoteDataTrack) {

            }

            @Override
            public void onDataTrackSubscriptionFailed(RemoteParticipant remoteParticipant,
                                                      RemoteDataTrackPublication remoteDataTrackPublication,
                                                      TwilioException twilioException) {

            }

            @Override
            public void onVideoTrackSubscribed(RemoteParticipant remoteParticipant,
                                               RemoteVideoTrackPublication remoteVideoTrackPublication,
                                               RemoteVideoTrack remoteVideoTrack) {

                addRemoteParticipantVideo(remoteVideoTrack);
            }

            @Override
            public void onVideoTrackUnsubscribed(RemoteParticipant remoteParticipant,
                                                 RemoteVideoTrackPublication remoteVideoTrackPublication,
                                                 RemoteVideoTrack remoteVideoTrack) {

                removeParticipantVideo(remoteVideoTrack);
            }

            @Override
            public void onVideoTrackSubscriptionFailed(RemoteParticipant remoteParticipant,
                                                       RemoteVideoTrackPublication remoteVideoTrackPublication,
                                                       TwilioException twilioException) {

                Toast.makeText(getApplicationContext(), twilioException.getExplanation(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAudioTrackEnabled(RemoteParticipant remoteParticipant,
                                            RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackDisabled(RemoteParticipant remoteParticipant,
                                             RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onVideoTrackEnabled(RemoteParticipant remoteParticipant,
                                            RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }

            @Override
            public void onVideoTrackDisabled(RemoteParticipant remoteParticipant,
                                             RemoteVideoTrackPublication remoteVideoTrackPublication) {
                Log.e(TAG, "onVideoTrackDisabled: isRecording? get state " + room.getState());
            }
        };
    }

    private void addRemoteParticipantVideo(VideoTrack videoTrack) {
        thumbnailVideoView.setMirror(false);
        videoTrack.addSink(thumbnailVideoView);
    }

    @Override
    public void onBackPressed() {
    }

    public void USBCamInitialization() {
        createAudioAndVideoTracks();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);
    }

    private void removeParticipantVideo(VideoTrack videoTrack) {
        videoTrack.removeSink(thumbnailVideoView);
    }

    private void requestPermissionForCameraAndMicrophone() {
        if (ActivityCompat.checkSelfPermission(this, permissionsRequired[0]) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, permissionsRequired[1]) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, permissionsRequired[2]) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, permissionsRequired[3]) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "cameraRequest:shouldShowRequestPermissionRationale ");
            Toast.makeText(this, R.string.permission_camera, Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(this, permissionsRequired, CAMERA_MIC_PERMISSION_REQUEST_CODE);

        } else {
            Log.d(TAG, "cameraRequest: else");
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                Log.d(TAG, "onKeyDown up: ");
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_PLAY_SOUND |AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                Log.d(TAG, "onKeyDown up: ");
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_PLAY_SOUND |AudioManager.FLAG_SHOW_UI);
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_MIC_PERMISSION_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                boolean allgranted = false;
                for (int i = 0; i <= permissions.length - 1; i++) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        allgranted = true;
                    } else {
                        allgranted = false;
                        break;
                    }
                }
                if (allgranted) {
                    Log.d(TAG, "onRequestPermissionsResult: all granted");
                    USBCamInitialization();
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, permissionsRequired[0]) ||
                        ActivityCompat.shouldShowRequestPermissionRationale(this, permissionsRequired[1]) ||
                        ActivityCompat.shouldShowRequestPermissionRationale(this, permissionsRequired[2]) ||
                        ActivityCompat.shouldShowRequestPermissionRationale(this, permissionsRequired[3])
                ) {
                    ActivityCompat.requestPermissions(this, permissionsRequired, CAMERA_MIC_PERMISSION_REQUEST_CODE);
                } else {

                    if (grantResults.length != 0) {
                        final Intent i = new Intent();
                        i.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.addCategory(Intent.CATEGORY_DEFAULT);
                        i.setData(Uri.parse("package:" + getApplicationContext().getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                        i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder.setTitle("app need permissions");
                        builder.setMessage("open settings?");
                        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                getApplicationContext().startActivity(i);
                            }
                        });
                        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                finish();
                            }
                        });
                        AlertDialog alertDialog  = builder.create();
                        alertDialog.setCancelable(false);
                        alertDialog.show();

                    }
                }

            } else {
                Toast.makeText(this, R.string.permission_camera, Toast.LENGTH_SHORT).show();
                ActivityCompat.requestPermissions(this, permissionsRequired, CAMERA_MIC_PERMISSION_REQUEST_CODE);
            }
        }
    }
}
