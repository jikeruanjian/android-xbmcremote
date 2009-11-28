/*
 *      Copyright (C) 2005-2009 Team XBMC
 *      http://xbmc.org
 *
 *  This Program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2, or (at your option)
 *  any later version.
 *
 *  This Program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with XBMC Remote; see the file license.  If not, write to
 *  the Free Software Foundation, 675 Mass Ave, Cambridge, MA 02139, USA.
 *  http://www.gnu.org/copyleft/gpl.html
 *
 */

package org.xbmc.android.remote.presentation.activity;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URL;

import org.xbmc.android.backend.httpapi.NowPlayingPollerThread;
import org.xbmc.android.remote.ConfigurationManager;
import org.xbmc.android.remote.R;
import org.xbmc.android.util.ConnectionManager;
import org.xbmc.android.util.ErrorHandler;
import org.xbmc.eventclient.ButtonCodes;
import org.xbmc.eventclient.EventClient;
import org.xbmc.httpapi.client.ControlClient;
import org.xbmc.httpapi.client.ControlClient.ICurrentlyPlaying;
import org.xbmc.httpapi.data.Song;
import org.xbmc.httpapi.type.SeekType;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Handler.Callback;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.SeekBar.OnSeekBarChangeListener;

public class NowPlayingActivity extends Activity implements Callback {
	
	static final String ACTION = "android.intent.action.VIEW";
	
	private ControlClient mControl;
	private EventClient mClient;
	private Handler mNowPlayingHandler;
	private TextView mAlbumView;
	private TextView mArtistView;
	private TextView mSongTitleView;
	private TextView mCounterLeftView;
	private TextView mCounterRightView;
	private ImageButton mPlayPauseView;
	private SeekBar mSeekBar;
	
	private ConfigurationManager mConfigurationManager;
	private boolean mErrorHandled = false;
	
	public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
       	setContentView(R.layout.nowplaying);
        	
  	  	mControl = ConnectionManager.getHttpClient(this).control;
  	  	mClient = ConnectionManager.getEventClient(this);
  	  	
		mSeekBar = (SeekBar) findViewById(R.id.NowPlayingProgress);
		mArtistView = (TextView) findViewById(R.id.ArtistTextView);
		mAlbumView = (TextView) findViewById(R.id.AlbumTextView);
		mSongTitleView = (TextView) findViewById(R.id.SongTextView);
		mCounterLeftView = (TextView)findViewById(R.id.now_playing_counter_left);
		mCounterRightView = (TextView)findViewById(R.id.now_playing_counter_right);
		mPlayPauseView = (ImageButton)findViewById(R.id.MediaPlayPauseButton);
  	  	
		// remove nasty top fading edge
		FrameLayout topFrame = (FrameLayout)findViewById(android.R.id.content);
		topFrame.setForeground(null);
  	  	
		// set titlebar text
  	  	((TextView)findViewById(R.id.titlebar_text)).setText("Now playing");
  	  	
  	  	mNowPlayingHandler = new Handler(this);

  	  	mConfigurationManager = ConfigurationManager.getInstance(this);
		mConfigurationManager.initKeyguard();
  	  	
  	  	setupButtons();
	}

	@Override
	protected void onResume() {
		super.onResume();
		mConfigurationManager.onActivityResume(this);
		checkIntent();
		ConnectionManager.getNowPlayingPoller(this).subscribe(mNowPlayingHandler);;
	}

	@Override
	protected void onPause() {
		super.onPause();
		ConnectionManager.getNowPlayingPoller(this).unSubscribe(mNowPlayingHandler);
		mConfigurationManager.onActivityPause();
		if(isTaskRoot()){
			Intent intent = new Intent(NowPlayingActivity.this, HomeActivity.class );
			NowPlayingActivity.this.startActivity(intent);
		}
	}
	
	private void setupButtons() {
		mSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser && !seekBar.isInTouchMode())
					mControl.seek(SeekType.absolute, progress);
			}
			public void onStartTrackingTouch(SeekBar seekBar) { }
			public void onStopTrackingTouch(SeekBar seekBar) {
				mControl.seek(SeekType.absolute, seekBar.getProgress());
			}
		});
		
		// setup buttons
		findViewById(R.id.MediaPreviousButton).setOnClickListener(new OnRemoteAction(ButtonCodes.REMOTE_SKIP_MINUS));
		findViewById(R.id.MediaStopButton).setOnClickListener(new OnRemoteAction(ButtonCodes.REMOTE_STOP));
		findViewById(R.id.MediaPlayPauseButton).setOnClickListener(new OnRemoteAction(ButtonCodes.REMOTE_PAUSE));
		findViewById(R.id.MediaNextButton).setOnClickListener(new OnRemoteAction(ButtonCodes.REMOTE_SKIP_PLUS));
		
		// playlist button
		findViewById(R.id.MediaPlaylistButton).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				startActivity(new Intent(NowPlayingActivity.this, PlaylistActivity.class));
			}
		});
	}
	
	/**
	 * This is called from the thread with a message containing updated
	 * info of what's currently playing.
	 * @param msg Message object containing currently playing info
	 */
	public synchronized boolean handleMessage(Message msg) {
		
		final Bundle data = msg.getData();
		final ICurrentlyPlaying currentlyPlaying = (ICurrentlyPlaying)data.getSerializable(NowPlayingPollerThread.BUNDLE_CURRENTLY_PLAYING);

		switch (msg.what) {
		case NowPlayingPollerThread.MESSAGE_PROGRESS_CHANGED: 
			if (!mSeekBar.isPressed()) {
				mSeekBar.setProgress(Math.round(currentlyPlaying.getPercentage()));
			}
			if (currentlyPlaying.isPlaying()) {
				mSeekBar.setEnabled(currentlyPlaying.getDuration() != 0);
				mCounterLeftView.setText(Song.getDuration(currentlyPlaying.getTime() + 1));
				mCounterRightView.setText(currentlyPlaying.getDuration() == 0 ? "unknown" : "-" + Song.getDuration(currentlyPlaying.getDuration() - currentlyPlaying.getTime() - 1));
				mPlayPauseView.setBackgroundResource(R.drawable.now_playing_pause);
			} else {
				mSeekBar.setEnabled(false);
				mCounterLeftView.setText("");
				mCounterRightView.setText("");
				mPlayPauseView.setBackgroundResource(R.drawable.now_playing_play);
			}
			return true;
		
		case NowPlayingPollerThread.MESSAGE_TRACK_CHANGED:
			mArtistView.setText(currentlyPlaying.getArtist());
	  	  	mAlbumView.setText(currentlyPlaying.getAlbum());
	  	  	mSongTitleView.setText(currentlyPlaying.getTitle());
	  	  	return true;
	  	  	
		case NowPlayingPollerThread.MESSAGE_COVER_CHANGED:
			final ImageView cover = (ImageView) findViewById(R.id.CoverImage);
			cover.setImageDrawable(ConnectionManager.getNowPlayingPoller(this).getNowPlayingCover());
			return true;
			
		case NowPlayingPollerThread.MESSAGE_CONNECTION_ERROR:
			Log.i("NOWPLAYNING","Received connection error from poller!");
			
			if(!mErrorHandled) {
				mErrorHandled = true;
				new ErrorHandler().handle(new ConnectException());
			}
			return true;
			
		case NowPlayingPollerThread.MESSAGE_RECONFIGURE:
			new Thread(){
				public void run(){
					try{
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						Log.e("NowPlayingActivity", Log.getStackTraceString(e));
					}
					ConnectionManager.getNowPlayingPoller(NowPlayingActivity.this).subscribe(mNowPlayingHandler);					
				}
			}.start();

			return true;
		default:
			return false;
		}
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		EventClient client = ConnectionManager.getEventClient(this);	
		try {
			switch (keyCode) {
				case KeyEvent.KEYCODE_VOLUME_UP:
					client.sendButton("R1", ButtonCodes.REMOTE_VOLUME_PLUS, false, true, true, (short)0, (byte)0);
					return true;
				case KeyEvent.KEYCODE_VOLUME_DOWN:
					client.sendButton("R1", ButtonCodes.REMOTE_VOLUME_MINUS, false, true, true, (short)0, (byte)0);
					return true;
			}
		} catch (IOException e) {
			return false;
		}
		return super.onKeyDown(keyCode, event);
	}

	/**
	 * Checks the intent that created/resumed this activity. Used to see if we are being handed
	 * an URL that should be passed to XBMC.
	 */
	private void checkIntent(){
		Intent intent = getIntent();
		final String action = intent.getAction();
		if(action != null) {
			Log.i("CHECKINTENT", action);
			if (action.equals(ACTION)){
				final String path = intent.getData().toString();
				if(path == null || path.equals(""))
					return;
				try{
					new URL(path);
				} catch(MalformedURLException e) {
					return;
				}
				
				final AlertDialog.Builder builder = new AlertDialog.Builder(this);
				builder.setTitle("Play URL on XBMC?");
				builder.setMessage("Do you want to play\n" + path + "\non XBMC?");
				builder.setCancelable(true);
				builder.setIcon(R.drawable.icon);
				builder.setNeutralButton("Yes", new android.content.DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						 new Thread(){
							 public void run(){
								 Looper.prepare();
								 mControl.playUrl(path);
								 Looper.loop();
							 }
						 }.start();
						 //ConnectionManager.getNowPlayingPoller(NowPlayingActivity.this).subscribe(mNowPlayingHandler);
					}
				});
				builder.setCancelable(true);
				builder.setNegativeButton("No", new android.content.DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
						finish();
					}
				});
				
				final AlertDialog alert = builder.create();
				try {
					alert.show();
				} catch (Exception e) {
					e.printStackTrace();
				}
				//cleanup so we won't trigger again.
				intent.setAction(null);
				intent.setData(null);
			}
		}
	}
	
	/**
	 * Handles the push- release button code. Switches image of the pressed
	 * button, vibrates and executes command.
	 */
	private class OnRemoteAction implements OnClickListener {
		private final String mAction;
		public OnRemoteAction(String action) {
			mAction = action;
		}
		public void onClick(View v) {
			try {
				mClient.sendButton("R1", mAction, false, true, true, (short)0, (byte)0);
			} catch (IOException e) { }
		}
	}
}