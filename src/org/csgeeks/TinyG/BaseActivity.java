package org.csgeeks.TinyG;

// Copyright 2012 Matthew Stock

import org.csgeeks.TinyG.Net.TinyGNetwork;
import org.csgeeks.TinyG.Support.*;
import org.csgeeks.TinyG.Support.TinyGService.TinyGBinder;
import org.csgeeks.TinyG.USBAccessory.USBAccessoryService;
import org.csgeeks.TinyG.USBHost.USBHostService;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.ActionBar.Tab;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

public class BaseActivity extends SherlockFragmentActivity implements
	 	JogFragment.JogFragmentListener, MotorFragment.MotorFragmentListener, AxisFragment.AxisFragmentListener,
		SystemFragment.SystemFragmentListener {
	private static final String TAG = "TinyG";
	private TinyGService tinyg = null;
	private int bindType = 0;
	private boolean connected = false;
	private boolean pendingConnect = false;
	private ServiceConnection currentServiceConnection;
	private PrefsListener mPreferencesListener;
	private BroadcastReceiver mIntentReceiver;

	@Override
	public void onResume() {
		IntentFilter updateFilter = new IntentFilter();
		updateFilter.addAction(TinyGService.STATUS);
		updateFilter.addAction(TinyGService.CONNECTION_STATUS);
		updateFilter.addAction(TinyGService.JSON_ERROR);
		updateFilter.addAction(TinyGService.AXIS_UPDATE);
		mIntentReceiver = new TinyGServiceReceiver();
		registerReceiver(mIntentReceiver, updateFilter);

		super.onResume();
	}

	@Override
	public void onPause() {
		unregisterReceiver(mIntentReceiver);
		super.onPause();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		final ActionBar actionBar = getSupportActionBar();

		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
		Resources res = getResources();
		String[] tabs = res.getStringArray(R.array.tabArray);
		MyTabListener tabListener = new MyTabListener();
		for (int i = 0; i < tabs.length; i++) {
			Tab tab = actionBar.newTab();
			tab.setText(tabs[i]);
			tab.setTag(tabs[i]);
			tab.setTabListener(tabListener);
			actionBar.addTab(tab);
		}

		// Force landscape for now, since we don't really handle the loss of the
		// binding
		// (and subsequent destruction of the service) very well. Revisit later.
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		setContentView(R.layout.main);

		Context mContext = getApplicationContext();
		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		bindType = Integer.parseInt(settings.getString("tgfx_driver", "0"));

		mPreferencesListener = new PrefsListener();
		settings.registerOnSharedPreferenceChangeListener(mPreferencesListener);

		if (savedInstanceState != null) {
			restoreState(savedInstanceState);
		}
	}

	private boolean bindDriver(ServiceConnection s) {
		switch (bindType) {
		case 0: // Network
			return bindService(new Intent(getApplicationContext(),
					TinyGNetwork.class), s, Context.BIND_AUTO_CREATE);
		case 1: // USB host
			// Check to see if the platform supports USB host
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB_MR1) {
				Toast.makeText(this, R.string.no_usb_host, Toast.LENGTH_SHORT)
						.show();
				return false;
			}
			return bindService(new Intent(getApplicationContext(),
					USBHostService.class), s, Context.BIND_AUTO_CREATE);
		case 2: // USB accessory
			// Check to see if the platform support USB accessory
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
				Toast.makeText(this, R.string.no_usb_accessory,
						Toast.LENGTH_SHORT).show();
				return false;
			}
			return bindService(new Intent(getApplicationContext(),
					USBAccessoryService.class), s, Context.BIND_AUTO_CREATE);
		default:
			return false;
		}
	}

	@Override
	public void onDestroy() {
		if (tinyg != null) {
			unbindService(currentServiceConnection);
			tinyg = null;
		}
		super.onDestroy();
	}

	// This is how we get messages from the TinyG service. Two different message
	// types - a STATUS giving us
	// updates from an SR statement, and a CONNECTION_STATUS signal so that we
	// know if the service is connected
	// to the USB or network port.
	public class TinyGServiceReceiver extends BroadcastReceiver {
		@SuppressLint("NewApi")
		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle b = intent.getExtras();
			String action = intent.getAction();
			if (action.equals(TinyGService.JSON_ERROR)) {
//				Toast.makeText(BaseActivity.this, b.getString("error"), Toast.LENGTH_SHORT)
//				.show();
			}
			if (action.equals(TinyGService.STATUS)) {
				StatusFragment sf = (StatusFragment) getSupportFragmentManager()
						.findFragmentById(R.id.statusF);
				sf.updateState(b);
				Fragment f = getSupportFragmentManager().findFragmentById(
						R.id.tabview);
				if (f != null && f.getClass() == FileFragment.class && tinyg != null)
					((FileFragment) f).nextLine(b.getInt("line"));		
			}
			if (action.equals(TinyGService.AXIS_UPDATE)) {
				Fragment f = getSupportFragmentManager().findFragmentById(
						R.id.tabview);
				if (f != null && f.getClass() == JogFragment.class && tinyg != null)
					((JogFragment) f).updateState(tinyg.getMachine());		
			}
			if (action.equals(TinyGService.CONNECTION_STATUS)) {
				connected = b.getBoolean("connection");
				if (connected == false)
					pendingConnect = false;
				invalidateOptionsMenu();
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getSupportMenuInflater();
		inflater.inflate(R.menu.options, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem menuConnect = menu.findItem(R.id.connect);
		if (connected)
			menuConnect.setTitle(R.string.disconnect);
		else
			menuConnect.setTitle(R.string.connect);
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {
		case R.id.connect:
			if (pendingConnect) {
				Log.d(TAG, "Waiting for connection...");
				return true;
			}
			if (tinyg == null) {
				connected = false;
				currentServiceConnection = new DriverServiceConnection();
				bindDriver(currentServiceConnection);
				// We can't call connect until we know we have a binding.
				pendingConnect = true;
				Log.d(TAG, "Binding...");
				return true;
			}
			if (connected)
				tinyg.disconnect();
			else {
				Log.d(TAG, "Conn using old binding");
				tinyg.connect();
			}
			return true;
		case R.id.settings:
			startActivity(new Intent(this, EditPreferencesActivity.class));
			return true;
		case R.id.about:
			FragmentTransaction ft = getSupportFragmentManager()
					.beginTransaction();
			AboutFragment af = new AboutFragment();
			af.show(ft, "about");
			return true;
		case R.id.refresh:
			Fragment f = getSupportFragmentManager().findFragmentById(
					R.id.tabview);
			if (f != null && f.getClass() == FileFragment.class && ((FileFragment) f).isActive())
				return true;
			if (connected)
				tinyg.refresh();
			else 
				Toast.makeText(this, "Not connected!", Toast.LENGTH_SHORT)
						.show();
		
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("bindType", bindType);
		outState.putBoolean("connected", connected);
		Log.d(TAG, "onSaveInstanceState() connected state is " + connected);
	}

	private void restoreState(Bundle inState) {
		bindType = inState.getInt("bindType");
		connected = inState.getBoolean("connected");
		Log.d(TAG, "restoreState() connected state is " + connected);
	}

	public void myClickHandler(View view) {
		Fragment f = getSupportFragmentManager().findFragmentById(R.id.tabview);
		if (f == null)
			return;

		// Ugly!
		if (f.getClass() == MotorFragment.class)
			((MotorFragment) f).myClickHandler(view);
		if (f.getClass() == AxisFragment.class)
			((AxisFragment) f).myClickHandler(view);
		if (f.getClass() == FileFragment.class)
			((FileFragment) f).myClickHandler(view);
	}

	// We get a driver binding, and so we create a helper class that interacts
	// with the Messenger.
	// We can probably redo this as a subclass.
	private class DriverServiceConnection implements ServiceConnection {
		public void onServiceConnected(ComponentName className, IBinder service) {
			TinyGBinder binder = (TinyGBinder) service;
			Log.d(TAG, "Service connected");
			tinyg = binder.getService();
			if (pendingConnect) {
				tinyg.connect();
				pendingConnect = false;
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			Log.d(TAG, "Service disconnected");
			tinyg = null;
		}
	}

	// Make sure we rebind services if we change the preference.
	private class PrefsListener implements
			SharedPreferences.OnSharedPreferenceChangeListener {
		public void onSharedPreferenceChanged(
				SharedPreferences sharedPreferences, String key) {
			if (key.equals("debug")) {
				// Let the service know it should change logging if it's running
				Log.d(TAG, "Changing log debugging state");
				if (tinyg != null) {
					tinyg.logging();
				}
			}
			if (key.equals("tgfx_driver")) {
				Log.d(TAG, "Changing binding");
				bindType = Integer.parseInt(sharedPreferences.getString(
						"tgfx_driver", "0"));
				if (tinyg != null) {
					try {
						unbindService(currentServiceConnection);
						tinyg = null;
					} catch (IllegalArgumentException e) {
						Log.w(TAG, "trying to unbind a non-bound service");
					}
				}
				currentServiceConnection = new DriverServiceConnection();
				bindDriver(currentServiceConnection);
			}
		}
	}

	// Callbacks from the various tabs to pull get/put machine state.
	// Didn't want to try to have multiple service connections in multiple
	// fragments if possible.
	public void onSystemSelected() {
		if (tinyg == null)
			return;
		Bundle b = tinyg.getMachineStatus();
		Fragment f = getSupportFragmentManager().findFragmentById(R.id.tabview);
		if (f != null && f.getClass() == SystemFragment.class)
			((SystemFragment) f).updateState(b);
	}

	public void onSystemSaved(Bundle values) {
		if (tinyg == null)
			return;
		Bundle sys = tinyg.getMachineStatus();
		Bundle update = new Bundle(values);

		for (String value : values.keySet())
			if (sys.containsKey(value)
					&& sys.get(value).equals(values.get(value)))
				update.remove(value);

		if (!update.isEmpty())
			tinyg.putSystem(update);
	}

	public void onMotorSelected(int m) {
		if (tinyg == null)
			return;
		Bundle b = tinyg.getMotor(m);
		Fragment f = getSupportFragmentManager().findFragmentById(R.id.tabview);
		if (f != null && f.getClass() == MotorFragment.class)
			((MotorFragment) f).updateState(b);
	}

	// Look for changed values, and push them on to the service
	public void onMotorSaved(int mnum, Bundle values) {
		if (tinyg == null)
			return;
		Bundle motor = tinyg.getMotor(mnum);
		Bundle update = new Bundle(values);

		for (String value : values.keySet())
			if (motor.containsKey(value)
					&& motor.get(value).equals(values.get(value)))
				update.remove(value);

		if (!update.isEmpty())
			tinyg.putMotor(mnum, update);
	}

	public void onAxisSelected(int a) {
		if (tinyg == null)
			return;
		Bundle b = tinyg.getAxis(a);
		Fragment f = getSupportFragmentManager().findFragmentById(R.id.tabview);
		if (f != null && f.getClass() == AxisFragment.class)
			((AxisFragment) f).updateState(b);
	}

	public void onAxisSaved(int a, Bundle values) {
		if (tinyg == null)
			return;
		Bundle axis = tinyg.getAxis(a);
		Bundle update = new Bundle(values);

		Log.d(TAG, "new = " + values.toString());
		Log.d(TAG, "old = " + axis.toString());

		for (String value : values.keySet())
			if (axis.containsKey(value)
					&& axis.get(value).equals(values.get(value)))
				update.remove(value);

		if (!update.isEmpty())
			tinyg.putAxis(a, update);
	}

	public boolean connectionState() {
		return connected;
	}

	private class MyTabListener implements ActionBar.TabListener {

		public void onTabSelected(Tab tab, FragmentTransaction ft) {
			Fragment f;
			FragmentManager fm = getSupportFragmentManager();
			f = fm.findFragmentByTag((String) tab.getText());

			if (f == null) {
				if (tab.getText().equals("File"))
					f = new FileFragment();
				else if (tab.getText().equals("Motor"))
					f = new MotorFragment();
				else if (tab.getText().equals("Axis"))
					f = new AxisFragment();
				else if (tab.getText().equals("System"))
					f = new SystemFragment();
				else { // Jog
					f = new JogFragment();
					if (tinyg != null)
						((JogFragment) f).updateState(tinyg.getMachine());		
				}
				ft.add(R.id.tabview, f, (String) tab.getText());
			} else {
				if (f.isDetached())
					ft.attach(f);
			}
		}

		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
			FragmentManager fm = getSupportFragmentManager();
			Fragment f = fm.findFragmentByTag((String) tab.getText());
			if (f != null) {
				ft.detach(f);
			}
		}

		public void onTabReselected(Tab tab, FragmentTransaction ft) {
		}
	}

	public void sendGcode(String cmd) {
		if (tinyg == null || !connected)
			return;
		tinyg.send_gcode(cmd);
	}

	public void stopMove() {
		if (tinyg == null || !connected)
			return;
		tinyg.send_stop();
	}

	public void resumeMove() {
		if (tinyg == null || !connected)
			return;
		tinyg.send_resume();
		
	}
	
	public void pauseMove() {
		if (tinyg == null || !connected)
			return;
		tinyg.send_pause();		
	}
	
	public void sendReset() {
		if (tinyg == null || !connected)
			return;
		tinyg.send_reset();
	}
	
	public int queueSize() {
		if (tinyg == null || !connected)
			return -1;
		return tinyg.queueSize();
	}

	public void goHome() {
		String command = "g28.2 ";
		Bundle b;
		
		if (tinyg == null || !connected)
			return;
		for (int i=0; i < 3; i++) {
			b = tinyg.getAxis(i);
			if (b.getInt("am") == 1)
				command = command + Machine.axisIndexToName[i] + "0";
		}
		Log.d(TAG, "home command: " + command);
		tinyg.send_gcode(command);		
	}
}
