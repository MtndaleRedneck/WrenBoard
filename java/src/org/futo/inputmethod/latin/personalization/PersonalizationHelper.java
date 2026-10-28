/*
 * Copyright (C) 2013 The Android Open Source Project
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

package org.futo.inputmethod.latin.personalization;

import android.content.Context;
import android.util.Log;

import org.futo.inputmethod.latin.common.FileUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.SoftReference;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Helps handle and manage personalized dictionaries such as {@link UserHistoryDictionary}.
 */
public class PersonalizationHelper {
    private static final String TAG = PersonalizationHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final ConcurrentHashMap<String, SoftReference<UserHistoryDictionary>>
            sLangUserHistoryDictCache = new ConcurrentHashMap<>();

    @Nonnull
    public static UserHistoryDictionary getUserHistoryDictionary(
            final Context context, final Locale locale, @Nullable final String accountName) {
        String lookupStr = locale.toString();
        if (accountName != null) {
            lookupStr += "." + accountName;
        }
        synchronized (sLangUserHistoryDictCache) {
            if (sLangUserHistoryDictCache.containsKey(lookupStr)) {
                final SoftReference<UserHistoryDictionary> ref =
                        sLangUserHistoryDictCache.get(lookupStr);
                final UserHistoryDictionary dict = ref == null ? null : ref.get();
                if (dict != null) {
                    if (DEBUG) {
                        Log.d(TAG, "Use cached UserHistoryDictionary with lookup: " + lookupStr);
                    }
                    dict.reloadDictionaryIfRequired();
                    return dict;
                }
            }
            final UserHistoryDictionary dict = new UserHistoryDictionary(
                    context, locale, accountName);
            sLangUserHistoryDictCache.put(lookupStr, new SoftReference<>(dict));
            return dict;
        }
    }

    /** Waits until every task queued so far on the keyboard executor has run (it is single threaded). */
    private static boolean awaitKeyboardExecutor() {
        try {
            org.futo.inputmethod.latin.utils.ExecutorUtils
                    .getBackgroundExecutor(org.futo.inputmethod.latin.utils.ExecutorUtils.KEYBOARD)
                    .submit(new Runnable() { @Override public void run() {} })
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (final Exception e) {
            Log.w(TAG, "Timed out or failed waiting for dictionary tasks", e);
            return false;
        }
    }

    /** Writes pending learned words to their files and waits for it, so an export copies current data. */
    public static boolean flushAllAndWait() {
        synchronized (sLangUserHistoryDictCache) {
            for (final SoftReference<UserHistoryDictionary> ref : sLangUserHistoryDictCache.values()) {
                final UserHistoryDictionary dict = ref == null ? null : ref.get();
                if (dict != null) dict.asyncFlushBinaryDictionary();
            }
        }
        return awaitKeyboardExecutor();
    }

    /**
     * Before a restore replaces the files: close the live dictionaries WITHOUT flushing and wait until
     * every queued task has run, so no stale in-memory data is written over the restored files later.
     */
    public static boolean quiesceForRestore() {
        // Pending words are flushed first, so aborting a restore after this loses nothing.
        if (!flushAllAndWait()) return false;
        synchronized (sLangUserHistoryDictCache) {
            UserHistoryDictionary.forceUncleanClose = true;
            try {
                for (final SoftReference<UserHistoryDictionary> ref : sLangUserHistoryDictCache.values()) {
                    final UserHistoryDictionary dict = ref == null ? null : ref.get();
                    if (dict != null) dict.close();
                }
                return awaitKeyboardExecutor();
            } finally {
                UserHistoryDictionary.forceUncleanClose = false;
            }
        }
    }

    public static void removeAllUserHistoryDictionaries(final Context context) {
        synchronized (sLangUserHistoryDictCache) {
            for (final ConcurrentHashMap.Entry<String, SoftReference<UserHistoryDictionary>> entry
                    : sLangUserHistoryDictCache.entrySet()) {
                if (entry.getValue() != null) {
                    final UserHistoryDictionary dict = entry.getValue().get();
                    if (dict != null) {
                        dict.clear();
                    }
                }
            }
            sLangUserHistoryDictCache.clear();
            final File filesDir = context.getFilesDir();
            if (filesDir == null) {
                Log.e(TAG, "context.getFilesDir() returned null.");
                return;
            }
            final boolean filesDeleted = FileUtils.deleteFilteredFiles(
                    filesDir, new DictFilter(UserHistoryDictionary.NAME));
            if (!filesDeleted) {
                Log.e(TAG, "Cannot remove dictionary files. filesDir: " + filesDir.getAbsolutePath()
                        + ", dictNamePrefix: " + UserHistoryDictionary.NAME);
            }
        }
    }

    private static class DictFilter implements FilenameFilter {
        private final String mName;

        DictFilter(final String name) {
            mName = name;
        }

        @Override
        public boolean accept(final File dir, final String name) {
            return name.startsWith(mName);
        }
    }
}
