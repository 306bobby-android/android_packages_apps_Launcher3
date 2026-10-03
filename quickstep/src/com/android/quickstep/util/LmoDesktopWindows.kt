/*
 * SPDX-FileCopyrightText: 2026 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util

import android.os.IBinder
import android.os.ServiceManager
import android.os.SystemProperties
import android.util.Log
import com.libremobileos.freeform.ILMOFreeformDesktopListener
import com.libremobileos.freeform.ILMOFreeformUIService
import com.libremobileos.freeform.LMOFreeformDesktopWindow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mirror of the LMOFreeform windows hosted on desktop displays. Their tasks run on private
 * virtual displays, so launcher maps them back to the host display for the taskbar and Overview.
 */
object LmoDesktopWindows {
    private const val TAG = "LmoDesktopWindows"
    private const val SERVICE_NAME = "lmo_freeform"
    private const val PROP_ENABLED = "persist.sys.lmofreeform.desktop"

    @Volatile private var service: ILMOFreeformUIService? = null
    @Volatile private var windows: Array<LMOFreeformDesktopWindow> = emptyArray()
    private val changeListeners = CopyOnWriteArrayList<Runnable>()

    private val desktopListener =
        object : ILMOFreeformDesktopListener.Stub() {
            override fun onDesktopWindowsChanged() {
                refresh()
                changeListeners.forEach { it.run() }
            }
        }

    private val deathRecipient =
        IBinder.DeathRecipient {
            service = null
            windows = emptyArray()
            changeListeners.forEach { it.run() }
        }

    private fun connect(): ILMOFreeformUIService? {
        service?.let {
            return it
        }
        synchronized(this) {
            service?.let {
                return it
            }
            val binder = ServiceManager.checkService(SERVICE_NAME) ?: return null
            val lmo = ILMOFreeformUIService.Stub.asInterface(binder)
            try {
                lmo.registerDesktopListener(desktopListener)
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to $SERVICE_NAME", e)
                return null
            }
            service = lmo
            refresh()
            return lmo
        }
    }

    private fun refresh() {
        windows =
            try {
                service?.desktopWindows ?: emptyArray()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch desktop windows", e)
                emptyArray()
            }
    }

    fun isAvailable(): Boolean = connect() != null

    /** Mirrors the WM-side kill switch so launcher falls back to Shell with it. */
    fun isRoutingEnabled(): Boolean =
        SystemProperties.getBoolean(PROP_ENABLED, true) && isAvailable()

    /** [listener] may run on a binder thread. */
    fun addChangeListener(listener: Runnable) {
        connect()
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: Runnable) {
        changeListeners.remove(listener)
    }

    fun getWindowForTask(taskId: Int): LMOFreeformDesktopWindow? {
        connect()
        return windows.find { it.taskId == taskId }
    }

    fun getWindowsOnHost(hostDisplayId: Int): List<LMOFreeformDesktopWindow> {
        connect()
        return windows.filter { it.hostDisplayId == hostDisplayId }
    }

    /** Returns the display the LMO window is drawn on if [displayId] is an LMO display. */
    fun getHostDisplayId(displayId: Int): Int? {
        connect()
        return windows.find { it.displayId == displayId }?.hostDisplayId
    }

    /** Returns false if [taskId] is not an LMO desktop window, so the caller can handle it. */
    fun toggle(taskId: Int): Boolean {
        val lmo = connect() ?: return false
        if (windows.none { it.taskId == taskId }) return false
        return try {
            lmo.toggleDesktopWindow(taskId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle task $taskId", e)
            false
        }
    }
}
