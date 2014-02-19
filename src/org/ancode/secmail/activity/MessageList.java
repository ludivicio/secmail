package org.ancode.secmail.activity;

import java.util.Collection;
import java.util.List;

import org.ancode.secmail.Account;
import org.ancode.secmail.Account.SortType;
import org.ancode.secmail.K9;
import org.ancode.secmail.K9.SplitViewMode;
import org.ancode.secmail.Preferences;
import org.ancode.secmail.R;
import org.ancode.secmail.activity.setup.AccountSettings;
import org.ancode.secmail.activity.setup.FolderSettings;
import org.ancode.secmail.activity.setup.Prefs;
import org.ancode.secmail.controller.MessagingController;
import org.ancode.secmail.crypto.PgpData;
import org.ancode.secmail.fragment.MenuFragment;
import org.ancode.secmail.fragment.MessageListFragment;
import org.ancode.secmail.fragment.MessageListFragment.MessageListFragmentListener;
import org.ancode.secmail.fragment.MessageViewFragment;
import org.ancode.secmail.fragment.MessageViewFragment.MessageViewFragmentListener;
import org.ancode.secmail.guide.MessageListGuide;
import org.ancode.secmail.mail.Message;
import org.ancode.secmail.mail.crypto.v2.AsyncHttpTools;
import org.ancode.secmail.mail.crypto.v2.CryptoguardUiHelper;
import org.ancode.secmail.mail.crypto.v2.HttpPostUtil;
import org.ancode.secmail.mail.crypto.v2.PostResultV2;
import org.ancode.secmail.mail.store.StorageManager;
import org.ancode.secmail.search.LocalSearch;
import org.ancode.secmail.search.SearchAccount;
import org.ancode.secmail.search.SearchSpecification;
import org.ancode.secmail.search.SearchSpecification.Attribute;
import org.ancode.secmail.search.SearchSpecification.SearchCondition;
import org.ancode.secmail.search.SearchSpecification.Searchfield;
import org.ancode.secmail.view.MessageHeader;
import org.ancode.secmail.view.MessageTitleView;
import org.ancode.secmail.view.ViewSwitcher;
import org.ancode.secmail.view.ViewSwitcher.OnSwitchCompleteListener;

import android.app.Dialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentManager.OnBackStackChangedListener;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.jeremyfeinstein.slidingmenu.lib.SlidingMenu;

/**
 * MessageList is the primary user interface for the program. This Activity
 * shows a list of messages. From this Activity the user can perform all
 * standard message operations.
 */
public class MessageList extends K9FragmentActivity implements MessageListFragmentListener, MessageViewFragmentListener,
		OnBackStackChangedListener, OnSwitchCompleteListener /*, OnSwipeGestureListener*/{

	// for this activity
	private static final String EXTRA_SEARCH = "search";
	private static final String EXTRA_NO_THREADING = "no_threading";

	private static final String ACTION_SHORTCUT = "shortcut";
	private static final String EXTRA_SPECIAL_FOLDER = "special_folder";

	private static final String EXTRA_MESSAGE_REFERENCE = "message_reference";

	// used for remote search
	public static final String EXTRA_SEARCH_ACCOUNT = "org.ancode.secmail.search_account";
	private static final String EXTRA_SEARCH_FOLDER = "org.ancode.secmail.search_folder";

	private static final String STATE_DISPLAY_MODE = "displayMode";
	private static final String STATE_MESSAGE_LIST_WAS_DISPLAYED = "messageListWasDisplayed";

	// Used for navigating to next/previous message
	private static final int PREVIOUS = 1;
	private static final int NEXT = 2;

	public static void actionDisplaySearch(Context context,
			SearchSpecification search, boolean noThreading, boolean newTask) {
		actionDisplaySearch(context, search, noThreading, newTask, true);
	}

	public static void actionDisplaySearch(Context context,
			SearchSpecification search, boolean noThreading, boolean newTask,
			boolean clearTop) {
		context.startActivity(intentDisplaySearch(context, search, noThreading,
				newTask, clearTop));
	}

	public static Intent intentDisplaySearch(Context context,
			SearchSpecification search, boolean noThreading, boolean newTask,
			boolean clearTop) {
		Intent intent = new Intent(context, MessageList.class);
		intent.putExtra(EXTRA_SEARCH, search);
		intent.putExtra(EXTRA_NO_THREADING, noThreading);

		if (clearTop) {
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		}
		if (newTask) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		}

		return intent;
	}

	public static Intent shortcutIntent(Context context, String specialFolder) {
		Intent intent = new Intent(context, MessageList.class);
		intent.setAction(ACTION_SHORTCUT);
		intent.putExtra(EXTRA_SPECIAL_FOLDER, specialFolder);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

		return intent;
	}

	public static Intent actionDisplayMessageIntent(Context context,
			MessageReference messageReference) {
		Intent intent = new Intent(context, MessageList.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		intent.putExtra(EXTRA_MESSAGE_REFERENCE, messageReference);
		return intent;
	}

	private StorageManager.StorageListener mStorageListener = new StorageListenerImplementation();

	private ActionBar mActionBar;
	private View mActionBarMessageList;
	private View mActionBarMessageView;
	private MessageTitleView mActionBarSubject;
	private TextView mActionBarTitle;
	private TextView mActionBarSubTitle;
	private TextView mActionBarUnread;
	private Menu mMenu;

	private ViewGroup mMessageViewContainer;
	private View mMessageViewPlaceHolder;

	private MessageListFragment mMessageListFragment;
	private MessageViewFragment mMessageViewFragment;
	private MenuFragment mMenuFragment;
	
	private int mFirstBackStackId = -1;

	private Account mAccount;
	private String mFolderName;
	private LocalSearch mSearch;
	private boolean mSingleFolderMode;
	private boolean mSingleAccountMode;

	private ProgressBar mActionBarProgress;
	private MenuItem mMenuButtonCheckMail;
	private View mActionButtonIndeterminateProgress;
	private int mLastDirection = (K9.messageViewShowNext()) ? NEXT : PREVIOUS;

	/**
	 * {@code true} if the message list should be displayed as flat list (i.e.
	 * no threading) regardless whether or not message threading was enabled in
	 * the settings. This is used for filtered views, e.g. when only displaying
	 * the unread messages in a folder.
	 */
	private boolean mNoThreading;
	
	public enum DisplayMode {
		MESSAGE_LIST, MESSAGE_VIEW, SPLIT_VIEW
	}
	
	public DisplayMode mDisplayMode;
	
	private MessageReference mMessageReference;

	/**
	 * {@code true} when the message list was displayed once. This is used in
	 * {@link #onBackPressed()} to decide whether to go from the message view to
	 * the message list or finish the activity.
	 */
	private boolean mMessageListWasDisplayed = false;
	
	private ViewSwitcher mViewSwitcher;
	
	// modified by lxc at 2014-02-19
	// User guide.
	private MessageListGuide mGuide;
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (UpgradeDatabases.actionUpgradeDatabases(this, getIntent())) {
			finish();
			return;
		}

		if (useSplitView()) {
			setContentView(R.layout.split_message_list);
		} else {
			setContentView(R.layout.message_list);
			mViewSwitcher = (ViewSwitcher) findViewById(R.id.container);
			mViewSwitcher.setFirstInAnimation(AnimationUtils.loadAnimation(
					this, R.anim.slide_in_left));
			mViewSwitcher.setFirstOutAnimation(AnimationUtils.loadAnimation(
					this, R.anim.slide_out_right));
			mViewSwitcher.setSecondInAnimation(AnimationUtils.loadAnimation(
					this, R.anim.slide_in_right));
			mViewSwitcher.setSecondOutAnimation(AnimationUtils.loadAnimation(
					this, R.anim.slide_out_left));
			mViewSwitcher.setOnSwitchCompleteListener(this);
		}
		
		initializeActionBar();
		setBehindContentView(R.layout.slide_menu);
		initializeSlidingMenu();
		
		if (!decodeExtras(getIntent())) {
			return;
		}
		
		String name = mSearch.getName();
		if(!isSearchAccount(name)) {
			initializeMenuFragment();
		} else {
			SlidingMenu mSlidingMenu = getSlidingMenu();
			mSlidingMenu.setSlidingEnabled(false);
		}
		
		findFragments();
		initializeDisplayMode(savedInstanceState);
		initializeLayout();
		initializeFragments();
		
		displayViews();
		
//		ChangeLog cl = new ChangeLog(this);
//		if (cl.isFirstRun()) {
//			cl.getLogDialog().show();
//		}
		
		mGuide = new MessageListGuide(this);
		if(mGuide.isFristRun()) {
			mGuide.showGuide(R.layout.message_list_guide);
			mGuide.saveStatus();
		}
		
	}
	
	// modified by lxc at 2013-12-05
	private boolean isSearchAccount(String name) {
		String mAllMessagesTitle = getString(R.string.search_all_messages_title);
		String mUnifiedInboxTitle = getString(R.string.integrated_inbox_title);
		if(mAllMessagesTitle.equals(name) || mUnifiedInboxTitle.equals(name)) {
			return true;
		}		
		return false;
	}
	
	
	@Override
	public void onNewIntent(Intent intent) {
		super.onNewIntent(intent);

		setIntent(intent);

		if (mFirstBackStackId >= 0) {
			getSupportFragmentManager()
					.popBackStackImmediate(mFirstBackStackId,
							FragmentManager.POP_BACK_STACK_INCLUSIVE);
			mFirstBackStackId = -1;
		}
		
		removeMessageListFragment();
		removeMessageViewFragment();

		mMessageReference = null;
		mSearch = null;
		mFolderName = null;

		if (!decodeExtras(intent)) {
			return;
		}
		
		initializeDisplayMode(null);
		initializeFragments();
		displayViews();
		
	}


	private void initializeMenuFragment() {
		mMenuFragment = new MenuFragment();
		FragmentManager fragmentManager = getSupportFragmentManager();
		FragmentTransaction ft = fragmentManager.beginTransaction();
		ft.replace(R.id.leftMenu, mMenuFragment);
		ft.commit();
	}

	private void initializeSlidingMenu() {

		SlidingMenu mSlidingMenu = getSlidingMenu();
		mSlidingMenu.setMode(SlidingMenu.LEFT);
		
		int width = getWindowManager().getDefaultDisplay().getWidth();
		mSlidingMenu.setShadowWidth(width / 40);
		if (isTablet(this)) {
			mSlidingMenu.setBehindOffset(width / 2 + 40);
		} else {
			mSlidingMenu.setBehindOffset(width / 5 + 40);
		}
		
		mSlidingMenu.setFadeEnabled(true);
		mSlidingMenu.setFadeDegree(0.35f);
		mSlidingMenu.setTouchModeAbove(SlidingMenu.TOUCHMODE_MARGIN);
		
	}

	public static boolean isTablet(Context context) {
		return (context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE;
	}
	
	private void initializeActionBar() {
		mActionBar = getSupportActionBar();

		mActionBar.setDisplayShowCustomEnabled(true);
		mActionBar.setCustomView(R.layout.actionbar_custom);

		View customView = mActionBar.getCustomView();
		mActionBarMessageList = customView
				.findViewById(R.id.actionbar_message_list);
		mActionBarMessageView = customView
				.findViewById(R.id.actionbar_message_view);
		mActionBarSubject = (MessageTitleView) customView
				.findViewById(R.id.message_title_view);
		mActionBarTitle = (TextView) customView
				.findViewById(R.id.actionbar_title_first);
		mActionBarSubTitle = (TextView) customView
				.findViewById(R.id.actionbar_title_sub);
		mActionBarUnread = (TextView) customView
				.findViewById(R.id.actionbar_unread_count);
		mActionBarProgress = (ProgressBar) customView
				.findViewById(R.id.actionbar_progress);
		mActionButtonIndeterminateProgress = getLayoutInflater().inflate(
				R.layout.actionbar_indeterminate_progress_actionview, null);

		mActionBar.setDisplayHomeAsUpEnabled(true);
	}
	
	
	/**
	 * Get references to existing fragments if the activity was restarted.
	 */
	private void findFragments() {
		FragmentManager fragmentManager = getSupportFragmentManager();
		mMessageListFragment = (MessageListFragment) fragmentManager
				.findFragmentById(R.id.message_list_container);
		mMessageViewFragment = (MessageViewFragment) fragmentManager
				.findFragmentById(R.id.message_view_container);
	}

	/**
	 * Create fragment instances if necessary.
	 * 
	 * @see #findFragments()
	 */
	private void initializeFragments() {
		FragmentManager fragmentManager = getSupportFragmentManager();
		fragmentManager.addOnBackStackChangedListener(this);

		boolean hasMessageListFragment = (mMessageListFragment != null);

		if (!hasMessageListFragment) {
			FragmentTransaction ft = fragmentManager.beginTransaction();
			mMessageListFragment = MessageListFragment.newInstance(mSearch,
					false, (K9.isThreadedViewEnabled() && !mNoThreading));
			ft.add(R.id.message_list_container, mMessageListFragment);
			ft.commit();
		}

		// Check if the fragment wasn't restarted and has a MessageReference in
		// the arguments. If so, open the referenced message.
		if (!hasMessageListFragment && mMessageViewFragment == null && mMessageReference != null) {
			openMessage(mMessageReference);
		}
	}

	/**
	 * Set the initial display mode (message list, message view, or split view).
	 * 
	 * <p>
	 * <strong>Note:</strong> This method has to be called after
	 * {@link #findFragments()} because the result depends on the availability
	 * of a {@link MessageViewFragment} instance.
	 * </p>
	 * 
	 * @param savedInstanceState
	 *            The saved instance state that was passed to the activity as
	 *            argument to {@link #onCreate(Bundle)}. May be {@code null}.
	 */
	private void initializeDisplayMode(Bundle savedInstanceState) {
		if (useSplitView()) {
			mDisplayMode = DisplayMode.SPLIT_VIEW;
			return;
		}

		if (savedInstanceState != null) {
			DisplayMode savedDisplayMode = (DisplayMode) savedInstanceState
					.getSerializable(STATE_DISPLAY_MODE);
			if (savedDisplayMode != DisplayMode.SPLIT_VIEW) {
				mDisplayMode = savedDisplayMode;
				return;
			}
		}

		if (mMessageViewFragment != null || mMessageReference != null) {
			mDisplayMode = DisplayMode.MESSAGE_VIEW;
		} else {
			mDisplayMode = DisplayMode.MESSAGE_LIST;
		}
	}

	private boolean useSplitView() {
		SplitViewMode splitViewMode = K9.getSplitViewMode();
		int orientation = getResources().getConfiguration().orientation;

		return (splitViewMode == SplitViewMode.ALWAYS || (splitViewMode == SplitViewMode.WHEN_IN_LANDSCAPE && orientation == Configuration.ORIENTATION_LANDSCAPE));
	}

	private void initializeLayout() {
		mMessageViewContainer = (ViewGroup) findViewById(R.id.message_view_container);
		mMessageViewPlaceHolder = getLayoutInflater().inflate(R.layout.empty_message_view, null);
	}

	private void displayViews() {
		
		switch (mDisplayMode) {
		case MESSAGE_LIST: {
			showMessageList();
			break;
		}
		case MESSAGE_VIEW: {
			showMessageView();
			break;
		}
		case SPLIT_VIEW: {
			mMessageListWasDisplayed = true;
			if (mMessageViewFragment == null) {
				showMessageViewPlaceHolder();
			} else {
				MessageReference activeMessage = mMessageViewFragment
						.getMessageReference();
				if (activeMessage != null) {
					mMessageListFragment.setActiveMessage(activeMessage);
				}
			}
			break;
		}
		}
	}

	private boolean decodeExtras(Intent intent) {
		String action = intent.getAction();
		if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
			Uri uri = intent.getData();
			List<String> segmentList = uri.getPathSegments();

			String accountId = segmentList.get(0);
			Collection<Account> accounts = Preferences.getPreferences(this)
					.getAvailableAccounts();
			for (Account account : accounts) {
				if (String.valueOf(account.getAccountNumber())
						.equals(accountId)) {
					mMessageReference = new MessageReference();
					mMessageReference.accountUuid = account.getUuid();
					mMessageReference.folderName = segmentList.get(1);
					mMessageReference.uid = segmentList.get(2);
					break;
				}
			}
		} else if (ACTION_SHORTCUT.equals(action)) {
			// Handle shortcut intents
			String specialFolder = intent.getStringExtra(EXTRA_SPECIAL_FOLDER);
			if (SearchAccount.UNIFIED_INBOX.equals(specialFolder)) {
				mSearch = SearchAccount.createUnifiedInboxAccount(this)
						.getRelatedSearch();
			} else if (SearchAccount.ALL_MESSAGES.equals(specialFolder)) {
				mSearch = SearchAccount.createAllMessagesAccount(this)
						.getRelatedSearch();
			}
		} else if (intent.getStringExtra(SearchManager.QUERY) != null) {
			// check if this intent comes from the system search ( remote )
			if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
				// Query was received from Search Dialog
				String query = intent.getStringExtra(SearchManager.QUERY);

				mSearch = new LocalSearch(getString(R.string.search_results));
				mSearch.setManualSearch(true);
				mNoThreading = true;

				mSearch.or(new SearchCondition(Searchfield.SENDER,
						Attribute.CONTAINS, query));
				mSearch.or(new SearchCondition(Searchfield.SUBJECT,
						Attribute.CONTAINS, query));
				mSearch.or(new SearchCondition(Searchfield.MESSAGE_CONTENTS,
						Attribute.CONTAINS, query));

				Bundle appData = intent.getBundleExtra(SearchManager.APP_DATA);
				if (appData != null) {
					mSearch.addAccountUuid(appData
							.getString(EXTRA_SEARCH_ACCOUNT));
					// searches started from a folder list activity will provide
					// an account, but no folder
					if (appData.getString(EXTRA_SEARCH_FOLDER) != null) {
						mSearch.addAllowedFolder(appData
								.getString(EXTRA_SEARCH_FOLDER));
					}
				} else {
					mSearch.addAccountUuid(LocalSearch.ALL_ACCOUNTS);
				}
			}
		} else {
			// regular LocalSearch object was passed
			mSearch = intent.getParcelableExtra(EXTRA_SEARCH);
			mNoThreading = intent.getBooleanExtra(EXTRA_NO_THREADING, false);
		}

		if (mMessageReference == null) {
			mMessageReference = intent
					.getParcelableExtra(EXTRA_MESSAGE_REFERENCE);
		}

		if (mMessageReference != null) {
			mSearch = new LocalSearch();
			mSearch.addAccountUuid(mMessageReference.accountUuid);
			mSearch.addAllowedFolder(mMessageReference.folderName);
		}

		if (mSearch == null) {
			// We've most likely been started by an old unread widget
			String accountUuid = intent.getStringExtra("account");
			String folderName = intent.getStringExtra("folder");

			mSearch = new LocalSearch(folderName);
			mSearch.addAccountUuid((accountUuid == null) ? "invalid"
					: accountUuid);
			if (folderName != null) {
				mSearch.addAllowedFolder(folderName);
			}
		}

		Preferences prefs = Preferences.getPreferences(getApplicationContext());

		String[] accountUuids = mSearch.getAccountUuids();
		if (mSearch.searchAllAccounts()) {
			Account[] accounts = prefs.getAccounts();
			mSingleAccountMode = (accounts.length == 1);
			if (mSingleAccountMode) {
				mAccount = accounts[0];
			}
		} else {
			mSingleAccountMode = (accountUuids.length == 1);
			if (mSingleAccountMode) {
				mAccount = prefs.getAccount(accountUuids[0]);
			}
		}
		mSingleFolderMode = mSingleAccountMode
				&& (mSearch.getFolderNames().size() == 1);

		if (mSingleAccountMode
				&& (mAccount == null || !mAccount.isAvailable(this))) {
			Log.i(K9.LOG_TAG, "not opening MessageList of unavailable account");
			onAccountUnavailable();
			return false;
		}

		if (mSingleFolderMode) {
			mFolderName = mSearch.getFolderNames().get(0);
		}

		// now we know if we are in single account mode and need a subtitle
		mActionBarSubTitle.setVisibility((!mSingleFolderMode) ? View.GONE
				: View.VISIBLE);

		return true;
	}

	@Override
	public void onPause() {
		super.onPause();

		StorageManager.getInstance(getApplication()).removeListener(mStorageListener);
	}

	@Override
	public void onResume() {
		super.onResume();

		if (!(this instanceof Search)) {
			// necessary b/c no guarantee Search.onStop will be called before
			// MessageList.onResume
			// when returning from search results
			Search.setActive(false);
		}

		if (mAccount != null && !mAccount.isAvailable(this)) {
			onAccountUnavailable();
			return;
		}
		StorageManager.getInstance(getApplication()).addListener(
				mStorageListener);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		outState.putSerializable(STATE_DISPLAY_MODE, mDisplayMode);
		outState.putBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED,
				mMessageListWasDisplayed);
	}

	@Override
	public void onRestoreInstanceState(Bundle savedInstanceState) {
		mMessageListWasDisplayed = savedInstanceState
				.getBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		boolean ret = false;
		if (KeyEvent.ACTION_DOWN == event.getAction()) {
			ret = onCustomKeyDown(event.getKeyCode(), event);
		}
		if (!ret) {
			ret = super.dispatchKeyEvent(event);
		}
		return ret;
	}

	@Override
	public void onBackPressed() {
		
		// modified by lxc at 2014-02-10
		// Hide the guide screen.
		if(mGuide != null) {
			mGuide.hideGuide();
		}
		
		if (mDisplayMode == DisplayMode.MESSAGE_VIEW
				&& mMessageListWasDisplayed) {
			showMessageList();
		} else {
			super.onBackPressed();
		}
	}

	/**
	 * Handle hotkeys
	 * 
	 * <p>
	 * This method is called by {@link #dispatchKeyEvent(KeyEvent)} before any
	 * view had the chance to consume this key event.
	 * </p>
	 * 
	 * @param keyCode
	 *            The value in {@code event.getKeyCode()}.
	 * @param event
	 *            Description of the key event.
	 * 
	 * @return {@code true} if this event was consumed.
	 */
	public boolean onCustomKeyDown(final int keyCode, final KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_VOLUME_UP: {
			if (mMessageViewFragment != null
					&& mDisplayMode != DisplayMode.MESSAGE_LIST
					&& K9.useVolumeKeysForNavigationEnabled()) {
				showPreviousMessage();
				return true;
			} else if (mDisplayMode != DisplayMode.MESSAGE_VIEW
					&& K9.useVolumeKeysForListNavigationEnabled()) {
				mMessageListFragment.onMoveUp();
				return true;
			}

			break;
		}
		case KeyEvent.KEYCODE_VOLUME_DOWN: {
			if (mMessageViewFragment != null
					&& mDisplayMode != DisplayMode.MESSAGE_LIST
					&& K9.useVolumeKeysForNavigationEnabled()) {
				showNextMessage();
				return true;
			} else if (mDisplayMode != DisplayMode.MESSAGE_VIEW
					&& K9.useVolumeKeysForListNavigationEnabled()) {
				mMessageListFragment.onMoveDown();
				return true;
			}

			break;
		}
		case KeyEvent.KEYCODE_C: {
			mMessageListFragment.onCompose();
			return true;
		}
		case KeyEvent.KEYCODE_Q: {
			if (mMessageListFragment != null
					&& mMessageListFragment.isSingleAccountMode()) {
				onShowFolderList();
			}
			return true;
		}
		case KeyEvent.KEYCODE_O: {
			mMessageListFragment.onCycleSort();
			return true;
		}
		case KeyEvent.KEYCODE_I: {
			mMessageListFragment.onReverseSort();
			return true;
		}
		case KeyEvent.KEYCODE_DEL:
		case KeyEvent.KEYCODE_D: {
			if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
				mMessageListFragment.onDelete();
			} else if (mMessageViewFragment != null) {
				mMessageViewFragment.onDelete();
			}
			return true;
		}
		case KeyEvent.KEYCODE_S: {
			mMessageListFragment.toggleMessageSelect();
			return true;
		}
		case KeyEvent.KEYCODE_G: {
			if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
				mMessageListFragment.onToggleFlagged();
			} else if (mMessageViewFragment != null) {
				mMessageViewFragment.onToggleFlagged();
			}
			return true;
		}
		case KeyEvent.KEYCODE_M: {
			if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
				mMessageListFragment.onMove();
			} else if (mMessageViewFragment != null) {
				mMessageViewFragment.onMove();
			}
			return true;
		}
		case KeyEvent.KEYCODE_V: {
			if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
				mMessageListFragment.onArchive();
			} else if (mMessageViewFragment != null) {
				mMessageViewFragment.onArchive();
			}
			return true;
		}
		case KeyEvent.KEYCODE_Y: {
			if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
				mMessageListFragment.onCopy();
			} else if (mMessageViewFragment != null) {
				mMessageViewFragment.onCopy();
			}
			return true;
		}
		case KeyEvent.KEYCODE_Z: {
			if (mDisplayMode == DisplayMode.MESSAGE_LIST) {
				mMessageListFragment.onToggleRead();
			} else if (mMessageViewFragment != null) {
				mMessageViewFragment.onToggleRead();
			}
			return true;
		}
		case KeyEvent.KEYCODE_F: {
			if (mMessageViewFragment != null) {
				mMessageViewFragment.onForward();
			}
			return true;
		}
		case KeyEvent.KEYCODE_A: {
			if (mMessageViewFragment != null) {
				mMessageViewFragment.onReplyAll();
			}
			return true;
		}
		case KeyEvent.KEYCODE_R: {
			if (mMessageViewFragment != null) {
				mMessageViewFragment.onReply();
			}
			return true;
		}
		case KeyEvent.KEYCODE_J:
		case KeyEvent.KEYCODE_P: {
			if (mMessageViewFragment != null) {
				showPreviousMessage();
			}
			return true;
		}
		case KeyEvent.KEYCODE_N:
		case KeyEvent.KEYCODE_K: {
			if (mMessageViewFragment != null) {
				showNextMessage();
			}
			return true;
		}
		/*
		 * FIXME case KeyEvent.KEYCODE_Z: { mMessageViewFragment.zoom(event);
		 * return true; }
		 */
		case KeyEvent.KEYCODE_H: {
			Toast toast = Toast.makeText(this, R.string.message_list_help_key,
					Toast.LENGTH_LONG);
			toast.show();
			return true;
		}

		}

		return false;
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		// Swallow these events too to avoid the audible notification of a
		// volume change
		if (K9.useVolumeKeysForListNavigationEnabled()) {
			if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP)
					|| (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
				if (K9.DEBUG)
					Log.v(K9.LOG_TAG, "Swallowed key up.");
				return true;
			}
		}
		return super.onKeyUp(keyCode, event);
	}

	private void onAccounts() {
		Accounts.listAccounts(this);
		finish();
	}

	private void onShowFolderList() {
		FolderList.actionHandleAccount(this, mAccount);
		finish();
	}

	private void onEditPrefs() {
		Prefs.actionPrefs(this);
	}

	private void onEditAccount() {
		AccountSettings.actionSettings(this, mAccount);
	}

	@Override
	public boolean onSearchRequested() {
		return mMessageListFragment.onSearchRequested();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == android.R.id.home) {
			goBack();
			return true;
		} else if (itemId == R.id.compose) {
			mMessageListFragment.onCompose();
			return true;
		} else if (itemId == R.id.check_mail) {
			mMessageListFragment.checkMail();
			return true;
		} else if (itemId == R.id.set_sort_date) {
			mMessageListFragment.changeSort(SortType.SORT_DATE);
			return true;
		} else if (itemId == R.id.set_sort_arrival) {
			mMessageListFragment.changeSort(SortType.SORT_ARRIVAL);
			return true;
		} else if (itemId == R.id.set_sort_subject) {
			mMessageListFragment.changeSort(SortType.SORT_SUBJECT);
			return true;
		} else if (itemId == R.id.set_sort_sender) {
			mMessageListFragment.changeSort(SortType.SORT_SENDER);
			return true;
		} else if (itemId == R.id.set_sort_unread) {
			mMessageListFragment.changeSort(SortType.SORT_UNREAD);
			return true;
		} else if (itemId == R.id.set_sort_attach) {
			mMessageListFragment.changeSort(SortType.SORT_ATTACHMENT);
			return true;
		} else if (itemId == R.id.choose_message_item) {
			mMessageListFragment.manualSelectMessage();
			return true;
		} else if (itemId == R.id.app_settings) {
			onEditPrefs();
			return true;
		} else if (itemId == R.id.account_settings) {
			onEditAccount();
			return true;
		} else if (itemId == R.id.search) {
			mMessageListFragment.onSearchRequested();
			return true;
		} else if (itemId == R.id.search_remote) {
			mMessageListFragment.onRemoteSearch();
			return true;
		} else if (itemId == R.id.next_message) {
			showNextMessage();
			return true;
		} else if (itemId == R.id.previous_message) {
			showPreviousMessage();
			return true;
		} else if (itemId == R.id.delete) {
			mMessageViewFragment.onDelete();
			return true;
		} else if (itemId == R.id.reply) {
			mMessageViewFragment.onReply();
			return true;
		} else if (itemId == R.id.reply_all) {
			mMessageViewFragment.onReplyAll();
			return true;
		} else if (itemId == R.id.forward) {
			mMessageViewFragment.onForward();
			return true;
		} else if (itemId == R.id.toggle_unread) {
			mMessageViewFragment.onToggleRead();
			return true;
		} else if (itemId == R.id.spam) {
			mMessageViewFragment.onSpam();
			return true;
		} else if (itemId == R.id.move) {
			mMessageViewFragment.onMove();
			return true;
		} else if (itemId == R.id.select_text) {
			mMessageViewFragment.onSelectText();
			return true;
		} else if (itemId == R.id.show_headers || itemId == R.id.hide_headers) {
			mMessageViewFragment.onToggleAllHeadersView();
			updateMenu();
			return true;
		} else if (itemId == R.id.message_list_help) {
			mGuide = new MessageListGuide(this);
			mGuide.showGuide(R.layout.message_list_guide);
			return true;
		}

		if (!mSingleFolderMode) {
			// None of the options after this point are "safe" for search
			// results
			// TODO: This is not true for "unread" and "starred" searches in
			// regular folders
			return false;
		}

		if (itemId == R.id.send_messages) {
			mMessageListFragment.onSendPendingMessages();
			return true;
		} else if (itemId == R.id.folder_settings) {
			if (mFolderName != null) {
				FolderSettings.actionSettings(this, mAccount, mFolderName);
			}
			return true;
		} else if (itemId == R.id.expunge) {
			mMessageListFragment.onExpunge();
			return true;
		} else {
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getSupportMenuInflater().inflate(R.menu.message_list_option, menu);
		mMenu = menu;
		mMenuButtonCheckMail = menu.findItem(R.id.check_mail);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		configureMenu(menu);
		return true;
	}

	/**
	 * Hide menu items not appropriate for the current context.
	 * 
	 * <p>
	 * <strong>Note:</strong> Please adjust the comments in
	 * {@code res/menu/message_list_option.xml} if you change the visibility of
	 * a menu item in this method.
	 * </p>
	 * 
	 * @param menu
	 *            The {@link Menu} instance that should be modified. May be
	 *            {@code null}; in that case the method does nothing and
	 *            immediately returns.
	 */
	private void configureMenu(Menu menu) {
		if (menu == null) {
			return;
		}
		
		// Set visibility of account/folder settings menu items
		if (mMessageListFragment == null) {
			menu.findItem(R.id.account_settings).setVisible(false);
			menu.findItem(R.id.folder_settings).setVisible(false);
		} else {
			menu.findItem(R.id.account_settings).setVisible(
					mMessageListFragment.isSingleAccountMode());
			menu.findItem(R.id.folder_settings).setVisible(
					mMessageListFragment.isSingleFolderMode());
		}

		/*
		 * Set visibility of menu items related to the message view
		 */

		if (mDisplayMode == DisplayMode.MESSAGE_LIST
				|| mMessageViewFragment == null
				|| !mMessageViewFragment.isInitialized()) {
			menu.findItem(R.id.next_message).setVisible(false);
			menu.findItem(R.id.previous_message).setVisible(false);
			menu.findItem(R.id.single_message_options).setVisible(false);
			menu.findItem(R.id.delete).setVisible(false);
			menu.findItem(R.id.move).setVisible(false);
			menu.findItem(R.id.spam).setVisible(false);
			menu.findItem(R.id.refile).setVisible(false);
			menu.findItem(R.id.toggle_unread).setVisible(false);
			menu.findItem(R.id.select_text).setVisible(false);
			menu.findItem(R.id.show_headers).setVisible(false);
			menu.findItem(R.id.hide_headers).setVisible(false);
		} else {
			// hide prev/next buttons in split mode
			if (mDisplayMode != DisplayMode.MESSAGE_VIEW) {
				menu.findItem(R.id.next_message).setVisible(false);
				menu.findItem(R.id.previous_message).setVisible(false);
			} else {
				MessageReference ref = mMessageViewFragment
						.getMessageReference();
				boolean initialized = (mMessageListFragment != null && mMessageListFragment
						.isLoadFinished());
				boolean canDoPrev = (initialized && !mMessageListFragment
						.isFirst(ref));
				boolean canDoNext = (initialized && !mMessageListFragment
						.isLast(ref));

				MenuItem prev = menu.findItem(R.id.previous_message);
				prev.setEnabled(canDoPrev);
				prev.getIcon().setAlpha(canDoPrev ? 255 : 127);

				MenuItem next = menu.findItem(R.id.next_message);
				next.setEnabled(canDoNext);
				next.getIcon().setAlpha(canDoNext ? 255 : 127);
			}

			// Set title of menu item to toggle the read state of the currently
			// displayed message
			if (mMessageViewFragment.isMessageRead()) {
				menu.findItem(R.id.toggle_unread).setTitle(
						R.string.mark_as_unread_action);
			} else {
				menu.findItem(R.id.toggle_unread).setTitle(
						R.string.mark_as_read_action);
			}

			// Jellybean has built-in long press selection support
			menu.findItem(R.id.select_text).setVisible(
					Build.VERSION.SDK_INT < 16);

			menu.findItem(R.id.delete).setVisible(
					K9.isMessageViewDeleteActionVisible());

			/*
			 * Set visibility of copy, move, archive, spam in action bar and
			 * refile submenu
			 */
			Menu refileSubmenu = menu.findItem(R.id.refile).getSubMenu();

			if (mMessageViewFragment.isMoveCapable()) {
				boolean canMessageBeMovedToSpam = mMessageViewFragment
						.canMessageBeMovedToSpam();

				menu.findItem(R.id.move).setVisible(
						K9.isMessageViewMoveActionVisible());
				menu.findItem(R.id.spam).setVisible(
						canMessageBeMovedToSpam
								&& K9.isMessageViewSpamActionVisible());

				refileSubmenu.findItem(R.id.move).setVisible(true);
				refileSubmenu.findItem(R.id.spam).setVisible(
						canMessageBeMovedToSpam);
			} else {
				menu.findItem(R.id.move).setVisible(false);
				menu.findItem(R.id.spam).setVisible(false);
				menu.findItem(R.id.refile).setVisible(false);
			}

			if (mMessageViewFragment.allHeadersVisible()) {
				menu.findItem(R.id.show_headers).setVisible(false);
			} else {
				menu.findItem(R.id.hide_headers).setVisible(false);
			}
		}

		/*
		 * Set visibility of menu items related to the message list
		 */

		// Hide both search menu items by default and enable one when
		// appropriate
		menu.findItem(R.id.search).setVisible(false);
		menu.findItem(R.id.search_remote).setVisible(false);

		if (mDisplayMode == DisplayMode.MESSAGE_VIEW
				|| mMessageListFragment == null
				|| !mMessageListFragment.isInitialized()) {
			menu.findItem(R.id.check_mail).setVisible(false);
			menu.findItem(R.id.set_sort).setVisible(false);
			menu.findItem(R.id.choose_message_item).setVisible(false);
			menu.findItem(R.id.send_messages).setVisible(false);
			menu.findItem(R.id.expunge).setVisible(false);
			
			// Hide the help menu item, if not in message list mode.
			menu.findItem(R.id.message_list_help).setVisible(false);
		} else {
			menu.findItem(R.id.set_sort).setVisible(false);
			menu.findItem(R.id.choose_message_item).setVisible(true);

			if (!mMessageListFragment.isSingleAccountMode()) {
				menu.findItem(R.id.expunge).setVisible(false);
				menu.findItem(R.id.send_messages).setVisible(false);
			} else {
				menu.findItem(R.id.send_messages).setVisible(
						mMessageListFragment.isOutbox());
				menu.findItem(R.id.expunge).setVisible(
						mMessageListFragment.isRemoteFolder()
								&& mMessageListFragment
										.isAccountExpungeCapable());
			}

			menu.findItem(R.id.check_mail).setVisible(
					mMessageListFragment.isCheckMailSupported());

			// If this is an explicit local search, show the option to search on
			// the server
			if (!mMessageListFragment.isRemoteSearch()
					&& mMessageListFragment.isRemoteSearchAllowed()) {
				menu.findItem(R.id.search_remote).setVisible(true);
			} else if (!mMessageListFragment.isManualSearch()) {
				menu.findItem(R.id.search).setVisible(true);
			}
		}
	}

	protected void onAccountUnavailable() {
		finish();
		// TODO inform user about account unavailability using Toast
		Accounts.listAccounts(this);
	}

	public void setActionBarTitle(String title) {
		mActionBarTitle.setText(title);
	}

	public void setActionBarSubTitle(String subTitle) {
		mActionBarSubTitle.setText(subTitle);
	}

	public void setActionBarUnread(int unread) {
		if (unread == 0) {
			mActionBarUnread.setVisibility(View.GONE);
		} else {
			mActionBarUnread.setVisibility(View.VISIBLE);
			mActionBarUnread.setText(Integer.toString(unread));
		}
	}

	@Override
	public void setMessageListTitle(String title) {
		setActionBarTitle(title);
	}

	@Override
	public void setMessageListSubTitle(String subTitle) {
		setActionBarSubTitle(subTitle);
	}

	@Override
	public void setUnreadCount(int unread) {
		setActionBarUnread(unread);
	}

	@Override
	public void setMessageListProgress(int progress) {
		setSupportProgress(progress);
	}

	@Override
	public void openMessage(MessageReference messageReference) {
		Preferences prefs = Preferences.getPreferences(getApplicationContext());
		Account account = prefs.getAccount(messageReference.accountUuid);
		String folderName = messageReference.folderName;

		if (folderName.equals(account.getDraftsFolderName())) {
			MessageCompose.actionEditDraft(this, messageReference);
		} else {
			mMessageViewContainer.removeView(mMessageViewPlaceHolder);

			if (mMessageListFragment != null) {
				mMessageListFragment.setActiveMessage(messageReference);
			}

			MessageViewFragment fragment = MessageViewFragment
					.newInstance(messageReference);
			FragmentTransaction ft = getSupportFragmentManager()
					.beginTransaction();
			ft.replace(R.id.message_view_container, fragment);
			mMessageViewFragment = fragment;
			ft.commit();

			if (mDisplayMode != DisplayMode.SPLIT_VIEW) {
				showMessageView();
			}
		}
	}

	@Override
	public void onResendMessage(Message message) {
		MessageCompose.actionEditDraft(this, message.makeMessageReference());
	}

	@Override
	public void onForward(Message message) {
		MessageCompose.actionForward(this, message.getFolder().getAccount(),
				message, null);
	}

	@Override
	public void onReply(Message message) {
		MessageCompose.actionReply(this, message.getFolder().getAccount(),
				message, false, null);
	}

	@Override
	public void onReplyAll(Message message) {
		MessageCompose.actionReply(this, message.getFolder().getAccount(),
				message, true, null);
	}

	@Override
	public void onCompose(Account account) {
		MessageCompose.actionCompose(this, account);
	}

	@Override
	public void showMoreFromSameSender(String senderAddress) {
		LocalSearch tmpSearch = new LocalSearch("From " + senderAddress);
		tmpSearch.addAccountUuids(mSearch.getAccountUuids());
		tmpSearch.and(Searchfield.SENDER, senderAddress, Attribute.CONTAINS);

		MessageListFragment fragment = MessageListFragment.newInstance(
				tmpSearch, false, false);

		addMessageListFragment(fragment, true);
	}

	@Override
	public void onBackStackChanged() {
		findFragments();

		if (mDisplayMode == DisplayMode.SPLIT_VIEW) {
			showMessageViewPlaceHolder();
		}

		configureMenu(mMenu);
	}

	private final class StorageListenerImplementation implements
			StorageManager.StorageListener {
		@Override
		public void onUnmount(String providerId) {
			if (mAccount != null
					&& providerId.equals(mAccount.getLocalStorageProviderId())) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						onAccountUnavailable();
					}
				});
			}
		}

		@Override
		public void onMount(String providerId) {
			// no-op
		}
	}

	private void addMessageListFragment(MessageListFragment fragment,
			boolean addToBackStack) {
		FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

		ft.replace(R.id.message_list_container, fragment);
		if (addToBackStack)
			ft.addToBackStack(null);

		mMessageListFragment = fragment;

		int transactionId = ft.commit();
		if (transactionId >= 0 && mFirstBackStackId < 0) {
			mFirstBackStackId = transactionId;
		}
	}

	@Override
	public boolean startSearch(Account account, String folderName) {
		// If this search was started from a MessageList of a single folder,
		// pass along that folder info
		// so that we can enable remote search.
		if (account != null && folderName != null) {
			final Bundle appData = new Bundle();
			appData.putString(EXTRA_SEARCH_ACCOUNT, account.getUuid());
			appData.putString(EXTRA_SEARCH_FOLDER, folderName);
			startSearch(null, false, appData, false);
		} else {
			// TODO Handle the case where we're searching from within a search
			// result.
			startSearch(null, false, null, false);
		}

		return true;
	}

	@Override
	public void showThread(Account account, String folderName, long threadRootId) {
		showMessageViewPlaceHolder();

		LocalSearch tmpSearch = new LocalSearch();
		tmpSearch.addAccountUuid(account.getUuid());
		tmpSearch.and(Searchfield.THREAD_ID, String.valueOf(threadRootId),
				Attribute.EQUALS);

		MessageListFragment fragment = MessageListFragment.newInstance(
				tmpSearch, true, false);
		addMessageListFragment(fragment, true);
	}

	private void showMessageViewPlaceHolder() {
		removeMessageViewFragment();

		// Add placeholder view if necessary
		if (mMessageViewPlaceHolder.getParent() == null) {
			mMessageViewContainer.addView(mMessageViewPlaceHolder);
		}

		mMessageListFragment.setActiveMessage(null);
	}

	/**
	 * Remove MessageViewFragment if necessary.
	 */
	private void removeMessageViewFragment() {
		if (mMessageViewFragment != null) {
			FragmentTransaction ft = getSupportFragmentManager()
					.beginTransaction();
			ft.remove(mMessageViewFragment);
			mMessageViewFragment = null;
			ft.commit();

			showDefaultTitleView();
		}
	}

	private void removeMessageListFragment() {
		FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
		ft.remove(mMessageListFragment);
		mMessageListFragment = null;
		ft.commit();
	}

	@Override
	public void remoteSearchStarted() {
		// Remove action button for remote search
		configureMenu(mMenu);
	}

	@Override
	public void goBack() {
		FragmentManager fragmentManager = getSupportFragmentManager();
		if (mDisplayMode == DisplayMode.MESSAGE_VIEW) {
			showMessageList();
		} else if (fragmentManager.getBackStackEntryCount() > 0) {
			fragmentManager.popBackStack();
		} else if (mMessageListFragment != null && mMessageListFragment.isManualSearch()) {
			finish();
		} else if (!mSingleFolderMode) {
			onAccounts();
		} else {
			if( this.getSlidingMenu() != null ) {
				this.getSlidingMenu().showMenu();	
			}
		}
	}

	@Override
	public void enableActionBarProgress(boolean enable) {
		if (mMenuButtonCheckMail != null && mMenuButtonCheckMail.isVisible()) {
			mActionBarProgress.setVisibility(ProgressBar.GONE);
			if (enable) {
				mMenuButtonCheckMail
						.setActionView(mActionButtonIndeterminateProgress);
			} else {
				mMenuButtonCheckMail.setActionView(null);
			}
		} else {
			if (mMenuButtonCheckMail != null)
				mMenuButtonCheckMail.setActionView(null);
			if (enable) {
				mActionBarProgress.setVisibility(ProgressBar.VISIBLE);
			} else {
				mActionBarProgress.setVisibility(ProgressBar.GONE);
			}
		}
	}

	@Override
	public void displayMessageSubject(String subject) {
		if (mDisplayMode == DisplayMode.MESSAGE_VIEW) {
			mActionBarSubject.setText(subject);
		}
	}

	@Override
	public void onReply(Message message, PgpData pgpData) {
		MessageCompose.actionReply(this, mAccount, message, false,
				pgpData.getDecryptedData());
	}

	@Override
	public void onReplyAll(Message message, PgpData pgpData) {
		MessageCompose.actionReply(this, mAccount, message, true,
				pgpData.getDecryptedData());
	}

	@Override
	public void onForward(Message mMessage, PgpData mPgpData) {
		MessageCompose.actionForward(this, mAccount, mMessage,
				mPgpData.getDecryptedData());
	}

	@Override
	public void showNextMessageOrReturn() {
		if (K9.messageViewReturnToList() || !showLogicalNextMessage()) {
			if (mDisplayMode == DisplayMode.SPLIT_VIEW) {
				showMessageViewPlaceHolder();
			} else {
				showMessageList();
			}
		}
	}

	/**
	 * Shows the next message in the direction the user was displaying messages.
	 * 
	 * @return {@code true}
	 */
	private boolean showLogicalNextMessage() {
		boolean result = false;
		if (mLastDirection == NEXT) {
			result = showNextMessage();
		} else if (mLastDirection == PREVIOUS) {
			result = showPreviousMessage();
		}

		if (!result) {
			result = showNextMessage() || showPreviousMessage();
		}

		return result;
	}

	@Override
	public void setProgress(boolean enable) {
		setSupportProgressBarIndeterminateVisibility(enable);
	}

	@Override
	public void messageHeaderViewAvailable(MessageHeader header) {
		mActionBarSubject.setMessageHeader(header);
	}

	private boolean showNextMessage() {
		MessageReference ref = mMessageViewFragment.getMessageReference();
		if (ref != null) {
			if (mMessageListFragment.openNext(ref)) {
				mLastDirection = NEXT;
				return true;
			}
		}
		return false;
	}

	private boolean showPreviousMessage() {
		MessageReference ref = mMessageViewFragment.getMessageReference();
		if (ref != null) {
			if (mMessageListFragment.openPrevious(ref)) {
				mLastDirection = PREVIOUS;
				return true;
			}
		}
		return false;
	}

	private void showMessageList() {
		mMessageListWasDisplayed = true;
		mDisplayMode = DisplayMode.MESSAGE_LIST;
		mViewSwitcher.showFirstView();

		mMessageListFragment.setActiveMessage(null);

		showDefaultTitleView();
		configureMenu(mMenu);
	}

	private void showMessageView() {
		mDisplayMode = DisplayMode.MESSAGE_VIEW;

		if (!mMessageListWasDisplayed) {
			mViewSwitcher.setAnimateFirstView(false);
		}
		mViewSwitcher.showSecondView();

		showMessageTitleView();
		configureMenu(mMenu);
	}

	@Override
	public void updateMenu() {
		invalidateOptionsMenu();
	}

	@Override
	public void disableDeleteAction() {
		mMenu.findItem(R.id.delete).setEnabled(false);
	}

	private void showDefaultTitleView() {
		mActionBarMessageView.setVisibility(View.GONE);
		mActionBarMessageList.setVisibility(View.VISIBLE);

		if (mMessageListFragment != null) {
			mMessageListFragment.updateTitle();
		}

		mActionBarSubject.setMessageHeader(null);
	}

	private void showMessageTitleView() {
		mActionBarMessageList.setVisibility(View.GONE);
		mActionBarMessageView.setVisibility(View.VISIBLE);

		if (mMessageViewFragment != null) {
			displayMessageSubject(null);
			mMessageViewFragment.updateTitle();
		}
	}

	@Override
	public void onSwitchComplete(int displayedChild) {
		if (displayedChild == 0) {
			removeMessageViewFragment();
		}
	}
	
	public Account getAccount() {
    	return mAccount;
    }
	
	// modified by lxc at 2013-11-22
    private static final int DIALOG_REG_SUCCESS = 0;
	private static final int DIALOG_REG_FAILED = 1;
	private static final int DIALOG_CANCEL_REG = 2;
	private static final int DIALOG_PROTECT_ENABLED = 3;
	
	private ImageButton encryptButton;
	public void registDecryptService(ImageButton imageButton) {
		encryptButton = imageButton;
		showDialog(DIALOG_CANCEL_REG);
		encryptButton.setBackgroundDrawable(getResources().getDrawable(R.drawable.ic_button_unlock));
	}
	
	public void registEncryptService(ImageButton imageButton) {
		encryptButton = imageButton;

		// modified by lxc at 2013-11-11
		AsyncHttpTools.execute(new AsyncHttpTools.TaskListener() {
			
			@Override
			public PostResultV2 executeTask() {
				return HttpPostUtil.postRegRequest(mAccount, MessageList.this);
			}
			
			@Override
			public void callBack(PostResultV2 result) {
				if (result == null) {
					Toast.makeText(
							MessageList.this,
							getString(R.string.apply_reg_encrypt_network_anomaly),
							Toast.LENGTH_LONG).show();
					return;
				}
				if (result.isSuccess()) {
					mAccount.setApplyReg(true);
					mAccount.setDeviceUuid(result.getDeviceUuid());
					mAccount.save(Preferences.getPreferences(MessageList.this));
					showDialog(DIALOG_REG_SUCCESS);
				} else if (result.hasProtected()) {
					showDialog(DIALOG_PROTECT_ENABLED);
				} else {
					showDialog(DIALOG_REG_FAILED);
				}
			}
		});
	}
	
	public Handler mHandler = new Handler() {
	    
		public void handleMessage(android.os.Message msg) {
			
			if( msg.what == 0x001 ) {
				Account account = null;
				if(msg.obj instanceof Account) {
					account = (Account) msg.obj;
				}
				
				encryptButton.setBackgroundDrawable(getResources().getDrawable(R.drawable.ic_button_lock));
				
				CryptoguardUiHelper.openProtectDialog(MessageList.this, account);
			}
			
			super.handleMessage(msg);
		}
	};
	
    @Override
    public Dialog onCreateDialog(int id) {
        // Android recreates our dialogs on configuration changes even when they have been
        // dismissed. Make sure we have all information necessary before creating a new dialog.
        switch (id) {
	        // modified by lxc at 2013-11-22
	        // Case for secmail.
	        case DIALOG_REG_SUCCESS: {
				return ConfirmationDialog.create(this, id, R.string.apply_reg_encrypt_result_title,
						R.string.apply_reg_encrypt_result_success_message, R.string.okay_action, R.string.cancel_action,
						new Runnable() {
							@Override
							public void run() {
								
								// modified by lxc at 2013-11-05
								// Set the handler to update the ui.
								MessagingController.getInstance(getApplication()).setHandler(mHandler);
								MessagingController.getInstance(getApplication()).checkMail(getBaseContext(),
										mAccount, true, true, null);
							}
						});
			}
			case DIALOG_REG_FAILED: {
				return ConfirmationDialog.create(this, id, R.string.apply_reg_encrypt_result_title,
						R.string.apply_reg_encrypt_result_failed_message, R.string.okay_action, R.string.cancel_action,
						new Runnable() {
							@Override
							public void run() {
							}
						});
			}
			case DIALOG_CANCEL_REG: {
				return ConfirmationDialog.create(this, id, R.string.cancel_reg_encrypt_result_title,
						getString(R.string.cancel_reg_encrypt_message), R.string.okay_action, R.string.cancel_action,
						new Runnable() {
							@Override
							public void run() {
									// modified by lxc at 2013-11-01
									// cancel the register action
									mAccount.setRegCode(null);
									mAccount.setAesKey(null);
									mAccount.setDeviceUuid(null);
									mAccount.save(Preferences.getPreferences(MessageList.this));
								}
							});
			}
        }
		
		return super.onCreateDialog(id);

    }
    
    
    
	
}
