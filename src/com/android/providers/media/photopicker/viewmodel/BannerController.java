/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.viewmodel;

import static android.provider.MediaStore.getCurrentCloudProvider;

import static com.android.providers.media.photopicker.util.CloudProviderUtils.getAvailableCloudProviders;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getCloudMediaAccountName;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getProviderLabelForUser;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.util.XmlUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Banner Controller to store and handle the banner data per user for
 * {@link com.android.providers.media.photopicker.PhotoPickerActivity}.
 */
class BannerController {
    private static final String TAG = "BannerController";
    private static final String DATA_MEDIA_DIRECTORY_PATH = "/data/media/";
    private static final String LAST_CLOUD_PROVIDER_DATA_FILE_PATH_IN_USER_MEDIA_DIR =
            "/.transforms/picker/last_cloud_provider_info";
    /**
     * {@link #mCloudProviderDataMap} key to the last fetched
     * {@link android.provider.CloudMediaProvider} authority.
     */
    private static final String AUTHORITY = "authority";
    /**
     * {@link #mCloudProviderDataMap} key to the last fetched account name in the then fetched
     * {@link android.provider.CloudMediaProvider}.
     */
    private static final String ACCOUNT_NAME = "account_name";

    /**
     * {@link File} for persisting the last fetched {@link android.provider.CloudMediaProvider}
     * data.
     */
    private final File mLastCloudProviderDataFile;

    /**
     * Last fetched {@link android.provider.CloudMediaProvider} data.
     */
    private final Map<String, String> mCloudProviderDataMap = new HashMap<>();

    // Label of the current cloud media provider
    private String mCmpLabel;

    // Boolean Choose App Banner visibility
    private boolean mShowChooseAppBanner;

    // Boolean Choose App Banner visibility
    private boolean mShowCloudMediaAvailableBanner;

    BannerController(@NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        final String lastCloudProviderDataFilePath = DATA_MEDIA_DIRECTORY_PATH
                + userHandle.getIdentifier() + LAST_CLOUD_PROVIDER_DATA_FILE_PATH_IN_USER_MEDIA_DIR;
        mLastCloudProviderDataFile = new File(lastCloudProviderDataFilePath);
        loadCloudProviderInfo();

        initialise(context, configStore, userHandle);
    }

    /**
     * Same as {@link #initialise(Context, ConfigStore, UserHandle)}, renamed for readability.
     */
    void reset(@NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        initialise(context, configStore, userHandle);
    }

    /**
     * Initialise the banner controller data
     *
     * 0. Assert non-main thread.
     * 1. Fetch the latest cloud provider info.
     * 2. If the previous & new cloud provider infos are the same, No-op.
     * 3. Reset should show banners.
     * 4. Update the saved and cached cloud provider info with the latest info.
     *
     * Note : This method is expected to be called only in a non-main thread since we shouldn't
     * block the UI thread on the heavy Binder calls to fetch the cloud media provider info.
     */
    private void initialise(@NonNull Context context, @NonNull ConfigStore configStore,
            @NonNull UserHandle userHandle) {
        final String lastCmpAuthority = mCloudProviderDataMap.get(AUTHORITY);
        final String lastCmpAccountName = mCloudProviderDataMap.get(ACCOUNT_NAME);

        // Final String declarations for the new Cloud media provider authority & account name
        final String cmpAuthority, cmpAccountName;
        // TODO(b/245746037): Remove try-catch for the RuntimeException.
        //  Under the hood MediaStore.getCurrentCloudProvider() makes an IPC call to the primary
        //  MediaProvider process, where we currently perform a UID check (making sure that
        //  the call both sender and receiver belong to the same UID).
        //  This setup works for our "regular" PhotoPickerActivity (running in :PhotoPicker
        //  process), but does not work for our test applications (installed to a different
        //  UID), that provide a mock PhotoPickerActivity which will also run this code.
        //  SOLUTION: replace the UID check on the receiving end (in MediaProvider) with a
        //  check for MANAGE_CLOUD_MEDIA_PROVIDER permission.
        try {
            // 0. Assert non-main thread.
            assertNonMainThread();

            // 1. Fetch the latest cloud provider info.
            final ContentResolver contentResolver =
                    UserId.of(userHandle).getContentResolver(context);
            cmpAuthority = getCurrentCloudProvider(contentResolver);
            mCmpLabel = getProviderLabelForUser(context, userHandle, cmpAuthority);
            cmpAccountName = getCloudMediaAccountName(contentResolver, cmpAuthority);

            // Not logging the account name due to privacy concerns
            Log.d(TAG, "Current CloudMediaProvider authority: " + cmpAuthority + ", label: "
                    + mCmpLabel);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Could not fetch the current CloudMediaProvider", e);
            hideBanners();
            return;
        }

        // 2. If the previous & new cloud provider infos are the same, No-op.
        if (TextUtils.equals(lastCmpAuthority, cmpAuthority)
                && TextUtils.equals(lastCmpAccountName, cmpAccountName)) {
            // no-op
            return;
        }

        // 3. Reset should show banners.
        // mShowChooseAppBanner is true iff new authority is null and the available cloud
        // providers list is not empty.
        mShowChooseAppBanner = (cmpAuthority == null)
                && !getAvailableCloudProviders(context, configStore, userHandle).isEmpty();
        // mShowCloudMediaAvailableBanner is true iff the new authority AND account name are
        // NOT null while the old authority OR account is / are null.
        mShowCloudMediaAvailableBanner = cmpAuthority != null && cmpAccountName != null
                && (lastCmpAuthority == null || lastCmpAccountName == null);

        // 4. Update the saved and cached cloud provider info with the latest info.
        persistCloudProviderInfo(cmpAuthority, cmpAccountName);
    }

    /**
     * Hide all banners (Fallback for error scenarios)
     */
    private void hideBanners() {
        mCloudProviderDataMap.clear();
        mCmpLabel = null;
        mShowChooseAppBanner = false;
        mShowCloudMediaAvailableBanner = false;
    }

    /**
     * @return the authority of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderAuthority() {
        return mCloudProviderDataMap.get(AUTHORITY);
    }

    /**
     * @return the label of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderLabel() {
        return mCmpLabel;
    }

    /**
     * @return the account name of the current {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    String getCloudMediaProviderAccountName() {
        return mCloudProviderDataMap.get(ACCOUNT_NAME);
    }

    /**
     * @return the 'Choose App' banner visibility {@link #mShowChooseAppBanner}.
     */
    boolean shouldShowChooseAppBanner() {
        return mShowChooseAppBanner;
    }

    /**
     * @return the 'Cloud Media Available' banner visibility
     *         {@link #mShowCloudMediaAvailableBanner}.
     */
    boolean shouldShowCloudMediaAvailableBanner() {
        return mShowCloudMediaAvailableBanner;
    }

    /**
     * Dismiss (hide) the 'Choose App' banner
     *
     * Set the 'Choose App' banner visibility {@link #mShowChooseAppBanner} as {@code false}.
     */
    void onUserDismissedChooseAppBanner() {
        if (!mShowChooseAppBanner) {
            Log.wtf(TAG, "Choose app banner visibility for current user is false on"
                    + "dismiss");
        } else {
            mShowChooseAppBanner = false;
        }
    }

    /**
     * Dismiss (hide) the 'Cloud Media Available' banner
     *
     * Set the 'Cloud Media Available' banner visibility {@link #mShowCloudMediaAvailableBanner}
     * as {@code false}.
     */
    void onUserDismissedCloudMediaAvailableBanner() {
        if (!mShowCloudMediaAvailableBanner) {
            Log.wtf(TAG, "Cloud media available banner visibility for current user is false on"
                    + "dismiss");
        } else {
            mShowCloudMediaAvailableBanner = false;
        }
    }

    private static void assertNonMainThread() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            return;
        }

        throw new IllegalStateException("Expected to NOT be called from the main thread."
                + " Current thread: " + Thread.currentThread());
    }

    private void loadCloudProviderInfo() {
        FileInputStream fis = null;
        final Map<String, String> lastCloudProviderDataMap = new HashMap<>();
        try {
            if (!mLastCloudProviderDataFile.exists()) {
                return;
            }

            final AtomicFile atomicLastCloudProviderDataFile = new AtomicFile(
                    mLastCloudProviderDataFile);
            fis = atomicLastCloudProviderDataFile.openRead();
            lastCloudProviderDataMap.putAll(XmlUtils.readMapXml(fis));
        } catch (Exception e) {
            Log.w(TAG, "Could not load the cloud provider info.", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close the FileInputStream.", e);
                }
            }
            mCloudProviderDataMap.clear();
            mCloudProviderDataMap.putAll(lastCloudProviderDataMap);
        }
    }

    private void persistCloudProviderInfo(@Nullable String cmpAuthority,
            @Nullable String cmpAccountName) {
        mCloudProviderDataMap.clear();
        if (cmpAuthority != null) {
            mCloudProviderDataMap.put(AUTHORITY, cmpAuthority);
        }
        if (cmpAccountName != null) {
            mCloudProviderDataMap.put(ACCOUNT_NAME, cmpAccountName);
        }

        FileOutputStream fos = null;
        final AtomicFile atomicLastCloudProviderDataFile = new AtomicFile(
                mLastCloudProviderDataFile);

        try {
            fos = atomicLastCloudProviderDataFile.startWrite();
            XmlUtils.writeMapXml(mCloudProviderDataMap, fos);
            atomicLastCloudProviderDataFile.finishWrite(fos);
        } catch (Exception e) {
            atomicLastCloudProviderDataFile.failWrite(fos);
            Log.w(TAG, "Could not persist the cloud provider info.", e);
        }
    }
}