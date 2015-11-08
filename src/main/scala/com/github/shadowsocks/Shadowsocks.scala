/*
 * Shadowsocks - A shadowsocks client for Android
 * Copyright (C) 2014 <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */
package com.github.shadowsocks

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FileOutputStream, IOException, InputStream, OutputStream}
import java.util
import java.util.Locale

import android.app.backup.BackupManager
import android.app.{Activity, AlertDialog, ProgressDialog}
import android.content._
import android.content.res.AssetManager
import android.graphics.{Bitmap, Color, Typeface}
import android.net.{Uri, VpnService}
import android.os._
import android.preference.{Preference, SwitchPreference}
import android.support.design.widget.{FloatingActionButton, Snackbar}
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.{View, ViewGroup, ViewParent}
import android.webkit.{WebView, WebViewClient}
import android.widget._
import com.github.jorgecastilloprz.FABProgressCircle
import com.github.shadowsocks.aidl.{IShadowsocksService, IShadowsocksServiceCallback}
import com.github.shadowsocks.database._
import com.github.shadowsocks.preferences.{DropDownPreference, PasswordEditTextPreference, SummaryEditTextPreference}
import com.github.shadowsocks.utils._
import com.google.android.gms.ads.{AdRequest, AdSize, AdView}
import com.nostra13.universalimageloader.core.download.BaseImageDownloader

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ProfileIconDownloader(context: Context, connectTimeout: Int, readTimeout: Int)
  extends BaseImageDownloader(context, connectTimeout, readTimeout) {

  def this(context: Context) {
    this(context, 0, 0)
  }

  override def getStreamFromOtherSource(imageUri: String, extra: AnyRef): InputStream = {
    val text = imageUri.substring(Scheme.PROFILE.length)
    val size = Utils.dpToPx(context, 16)
    val idx = text.getBytes.last % 6
    val color = Seq(Color.MAGENTA, Color.GREEN, Color.YELLOW, Color.BLUE, Color.DKGRAY, Color.CYAN)(
      idx)
    val bitmap = Utils.getBitmap(text, size, size, color)

    val os = new ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
    new ByteArrayInputStream(os.toByteArray)
  }
}

object Typefaces {
  def get(c: Context, assetPath: String): Typeface = {
    cache synchronized {
      if (!cache.containsKey(assetPath)) {
        try {
          val t: Typeface = Typeface.createFromAsset(c.getAssets, assetPath)
          cache.put(assetPath, t)
        } catch {
          case e: Exception =>
            Log.e(TAG, "Could not get typeface '" + assetPath + "' because " + e.getMessage)
            return null
        }
      }
      return cache.get(assetPath)
    }
  }

  private final val TAG = "Typefaces"
  private final val cache = new util.Hashtable[String, Typeface]
}

object Shadowsocks {

  // Constants
  val TAG = "Shadowsocks"
  val REQUEST_CONNECT = 1

  val PREFS_NAME = "Shadowsocks"
  val PROXY_PREFS = Array(Key.proxy, Key.remotePort, Key.localPort, Key.sitekey, Key.encMethod)
  val FEATURE_PREFS = Array(Key.route, Key.isGlobalProxy, Key.proxyedApps, Key.isUdpDns, Key.isAuth, Key.isIpv6)
  val EXECUTABLES = Array(Executable.PDNSD, Executable.REDSOCKS, Executable.SS_TUNNEL, Executable.SS_LOCAL,
    Executable.TUN2SOCKS)

  // Helper functions
  def updateDropDownPreference(pref: Preference, value: String) {
    pref.asInstanceOf[DropDownPreference].setValue(value)
  }

  def updatePasswordEditTextPreference(pref: Preference, value: String) {
    pref.setSummary(value)
    pref.asInstanceOf[PasswordEditTextPreference].setText(value)
  }

  def updateSummaryEditTextPreference(pref: Preference, value: String) {
    pref.setSummary(value)
    pref.asInstanceOf[SummaryEditTextPreference].setText(value)
  }

  def updateSwitchPreference(pref: Preference, value: Boolean) {
    pref.asInstanceOf[SwitchPreference].setChecked(value)
  }

  def updatePreference(pref: Preference, name: String, profile: Profile) {
    name match {
      case Key.profileName => updateSummaryEditTextPreference(pref, profile.name)
      case Key.proxy => updateSummaryEditTextPreference(pref, profile.host)
      case Key.remotePort => updateSummaryEditTextPreference(pref, profile.remotePort.toString)
      case Key.localPort => updateSummaryEditTextPreference(pref, profile.localPort.toString)
      case Key.sitekey => updatePasswordEditTextPreference(pref, profile.password)
      case Key.encMethod => updateDropDownPreference(pref, profile.method)
      case Key.route => updateDropDownPreference(pref, profile.route)
      case Key.isGlobalProxy => updateSwitchPreference(pref, profile.global)
      case Key.isUdpDns => updateSwitchPreference(pref, profile.udpdns)
      case Key.isAuth => updateSwitchPreference(pref, profile.auth)
      case Key.isIpv6 => updateSwitchPreference(pref, profile.ipv6)
      case _ =>
    }
  }
}

class Shadowsocks
  extends AppCompatActivity {
  import Shadowsocks._

  // Variables
  private var serviceStarted = false
  var fab: FloatingActionButton = _
  var fabProgressCircle: FABProgressCircle = _
  var progressDialog: ProgressDialog = _
  var progressTag = -1
  var state = State.INIT
  var prepared = false
  var currentProfile = new Profile
  var vpnEnabled = -1

  // Services
  var currentServiceName = classOf[ShadowsocksNatService].getName
  var bgService: IShadowsocksService = null
  val callback = new IShadowsocksServiceCallback.Stub {
    override def stateChanged(state: Int, msg: String) {
      onStateChanged(state, msg)
    }
  }
  val connection = new ServiceConnection {
    override def onServiceConnected(name: ComponentName, service: IBinder) {
      // Initialize the background service
      bgService = IShadowsocksService.Stub.asInterface(service)
      try {
        bgService.registerCallback(callback)
      } catch {
        case ignored: RemoteException => // Nothing
      }
      // Update the UI
      if (fab != null) fab.setEnabled(true)
      stateUpdate()

      if (!ShadowsocksApplication.settings.getBoolean(ShadowsocksApplication.getVersionName, false)) {
        ShadowsocksApplication.settings.edit.putBoolean(ShadowsocksApplication.getVersionName, true).apply()
        recovery()
      }
    }

    override def onServiceDisconnected(name: ComponentName) {
      if (fab != null) fab.setEnabled(false)
      try {
        if (bgService != null) bgService.unregisterCallback(callback)
      } catch {
        case ignored: RemoteException => // Nothing
      }
      bgService = null
    }
  }

  private lazy val preferences =
    getFragmentManager.findFragmentById(android.R.id.content).asInstanceOf[ShadowsocksSettings]
  private lazy val greyTint = ContextCompat.getColorStateList(this, R.color.material_blue_grey_700)
  private lazy val greenTint = ContextCompat.getColorStateList(this, R.color.material_green_600)

  var handler = new Handler()

  private def changeSwitch(checked: Boolean) {
    serviceStarted = checked
    fab.setImageResource(if (checked) R.drawable.ic_cloud else R.drawable.ic_cloud_off)
    if (fab.isEnabled) {
      fab.setEnabled(false)
      handler.postDelayed(() => fab.setEnabled(true), 1000)
    }
  }

  private def showProgress(msg: Int): Handler = {
    clearDialog()
    progressDialog = ProgressDialog.show(this, "", getString(msg), true, false)
    progressTag = msg
    new Handler {
      override def handleMessage(msg: Message) {
        clearDialog()
      }
    }
  }

  private def copyAssets(path: String) {
    val assetManager: AssetManager = getAssets
    var files: Array[String] = null
    try {
      files = assetManager.list(path)
    } catch {
      case e: IOException =>
        Log.e(Shadowsocks.TAG, e.getMessage)
    }
    if (files != null) {
      for (file <- files) {
        var in: InputStream = null
        var out: OutputStream = null
        try {
          if (path.length > 0) {
            in = assetManager.open(path + "/" + file)
          } else {
            in = assetManager.open(file)
          }
          out = new FileOutputStream(Path.BASE + file)
          copyFile(in, out)
          in.close()
          in = null
          out.flush()
          out.close()
          out = null
        } catch {
          case ex: Exception =>
            Log.e(Shadowsocks.TAG, ex.getMessage)
        }
      }
    }
  }

  private def copyFile(in: InputStream, out: OutputStream) {
    val buffer: Array[Byte] = new Array[Byte](1024)
    var read: Int = 0
    while ( {
      read = in.read(buffer)
      read
    } != -1) {
      out.write(buffer, 0, read)
    }
  }

  private def crashRecovery() {
    val cmd = new ArrayBuffer[String]()

    for (task <- Array("ss-local", "ss-tunnel", "pdnsd", "redsocks", "tun2socks")) {
      cmd.append("chmod 666 %s%s-nat.pid".formatLocal(Locale.ENGLISH, Path.BASE, task))
      cmd.append("chmod 666 %s%s-vpn.pid".formatLocal(Locale.ENGLISH, Path.BASE, task))
    }
    Console.runRootCommand(cmd.toArray)
    cmd.clear()

    for (task <- Array("ss-local", "ss-tunnel", "pdnsd", "redsocks", "tun2socks")) {
      try {
        val pid_nat = scala.io.Source.fromFile(Path.BASE + task + "-nat.pid").mkString.trim.toInt
        val pid_vpn = scala.io.Source.fromFile(Path.BASE + task + "-vpn.pid").mkString.trim.toInt
        cmd.append("kill -9 %d".formatLocal(Locale.ENGLISH, pid_nat))
        cmd.append("kill -9 %d".formatLocal(Locale.ENGLISH, pid_vpn))
        Process.killProcess(pid_nat)
        Process.killProcess(pid_vpn)
      } catch {
        case e: Throwable => Log.e(Shadowsocks.TAG, "unable to kill " + task)
      }
      cmd.append("rm -f %s%s-nat.pid".formatLocal(Locale.ENGLISH, Path.BASE, task))
      cmd.append("rm -f %s%s-nat.conf".formatLocal(Locale.ENGLISH, Path.BASE, task))
      cmd.append("rm -f %s%s-vpn.pid".formatLocal(Locale.ENGLISH, Path.BASE, task))
      cmd.append("rm -f %s%s-vpn.conf".formatLocal(Locale.ENGLISH, Path.BASE, task))
    }
    Console.runCommand(cmd.toArray)
    Console.runRootCommand(cmd.toArray)
    Console.runRootCommand(Utils.getIptables + " -t nat -F OUTPUT")
  }

  def isTextEmpty(s: String, msg: String): Boolean = {
    if (s != null && s.length > 0) return false
    Snackbar.make(getWindow.getDecorView.findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show
    true
  }

  def cancelStart() {
    clearDialog()
    changeSwitch(checked = false)
  }

  def isReady: Boolean = {
    if (!checkText(Key.proxy)) return false
    if (!checkText(Key.sitekey)) return false
    if (!checkNumber(Key.localPort, low = false)) return false
    if (!checkNumber(Key.remotePort, low = true)) return false
    if (bgService == null) return false
    true
  }

  def prepareStartService() {
    Future {
      if (ShadowsocksApplication.isVpnEnabled) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
          startActivityForResult(intent, Shadowsocks.REQUEST_CONNECT)
        } else {
          onActivityResult(Shadowsocks.REQUEST_CONNECT, Activity.RESULT_OK, null)
        }
      } else {
        serviceStart()
      }
    }
  }

  def getLayoutView(view: ViewParent): LinearLayout = {
    view match {
      case layout: LinearLayout => layout
      case _ => if (view != null) getLayoutView(view.getParent) else null
    }
  }

  override def onCreate(savedInstanceState: Bundle) {

    super.onCreate(savedInstanceState)

    setContentView(R.layout.layout_main)
    if (ShadowsocksApplication.proxy == "198.199.101.152") {
      val adView = new AdView(this)
      adView.setAdUnitId("ca-app-pub-9097031975646651/7760346322")
      adView.setAdSize(AdSize.SMART_BANNER)
      preferences.getView.asInstanceOf[ViewGroup].addView(adView, 0)  // TODO: test
      adView.loadAd(new AdRequest.Builder().build())
    }

    // Update the profile
    if (!ShadowsocksApplication.settings.getBoolean(ShadowsocksApplication.getVersionName, false)) {
      currentProfile = ShadowsocksApplication.profileManager.create()
    }

    // Initialize the profile
    currentProfile = {
      ShadowsocksApplication.currentProfile getOrElse currentProfile
    }

    // Initialize action bar
    val toolbar = findViewById(R.id.toolbar).asInstanceOf[Toolbar]
    toolbar.setTitle(getString(R.string.screen_name))
    toolbar.setTitleTextAppearance(toolbar.getContext, R.style.Toolbar_Logo)
    val field = classOf[Toolbar].getDeclaredField("mTitleTextView")
    field.setAccessible(true)
    val title: TextView = field.get(toolbar).asInstanceOf[TextView]
    val tf: Typeface = Typefaces.get(this, "fonts/Iceland.ttf")
    if (tf != null) title.setTypeface(tf)
    title.setOnClickListener((view: View) => {
      ShadowsocksApplication.track(TAG, "about")
      showAbout()
    })

    fab = findViewById(R.id.fab).asInstanceOf[FloatingActionButton]
    fabProgressCircle = findViewById(R.id.fabProgressCircle).asInstanceOf[FABProgressCircle]
    fab.setOnClickListener((v: View) => {
      serviceStarted = !serviceStarted
      serviceStarted match {
        case true =>
          if (isReady)
            prepareStartService()
          else
            changeSwitch(checked = false)
        case false =>
          serviceStop()
          if (fab.isEnabled) {
            fab.setEnabled(false)
            handler.postDelayed(() => {
              fab.setEnabled(true)
            }, 1000)
          }
      }
    })
    fab.setOnLongClickListener((v: View) => {
      Utils.positionToast(Toast.makeText(this, if (serviceStarted) R.string.stop else R.string.connect,
        Toast.LENGTH_SHORT), fab, getWindow, 0, Utils.dpToPx(this, 8)).show
      true
    })

    // Bind to the service
    handler.post(() => {
      attachService()
    })
  }

  def attachService() {
    if (bgService == null) {
      val s = if (ShadowsocksApplication.isVpnEnabled) classOf[ShadowsocksVpnService]
      else classOf[ShadowsocksNatService]
      val intent = new Intent(this, s)
      intent.setAction(Action.SERVICE)
      bindService(intent, connection, Context.BIND_AUTO_CREATE)
      startService(new Intent(this, s))
    }
  }

  def deattachService() {
    if (bgService != null) {
      try {
        bgService.unregisterCallback(callback)
      } catch {
        case ignored: RemoteException => // Nothing
      }
      bgService = null
      unbindService(connection)
    }
  }

  def reloadProfile() {
    currentProfile = ShadowsocksApplication.currentProfile match {
      case Some(profile) => profile // updated
      case None =>                  // removed
        val profiles = ShadowsocksApplication.profileManager.getAllProfiles.getOrElse(List[Profile]())
        ShadowsocksApplication.profileId(if (profiles.isEmpty) -1 else profiles.head.id)
        ShadowsocksApplication.currentProfile getOrElse currentProfile
    }

    updatePreferenceScreen()
  }

  def addProfile(profile: Profile) {
    currentProfile = profile
    ShadowsocksApplication.profileManager.createOrUpdateProfile(currentProfile)
    ShadowsocksApplication.profileManager.reload(currentProfile.id)

    updatePreferenceScreen()
  }

  protected override def onPause() {
    super.onPause()
    ShadowsocksApplication.profileManager.save
    prepared = false
  }

  private def stateUpdate() {
    if (bgService != null) {
      bgService.getState match {
        case State.CONNECTING =>
          fab.setBackgroundTintList(greyTint)
          fabProgressCircle.show()
          changeSwitch(checked = true)
        case State.CONNECTED =>
          fab.setBackgroundTintList(greenTint)
          fabProgressCircle.hide()
          changeSwitch(checked = true)
        case State.STOPPING =>
          fab.setBackgroundTintList(greyTint)
          fabProgressCircle.show()
          changeSwitch(checked = false)
        case _ =>
          fab.setBackgroundTintList(greyTint)
          fabProgressCircle.hide()
          changeSwitch(checked = false)
      }
      state = bgService.getState
    }
  }

  protected override def onResume() {
    super.onResume()
    stateUpdate()
    ConfigUtils.refresh(this)

    // Check if current profile changed
    if (ShadowsocksApplication.profileId != currentProfile.id) reloadProfile()
  }

  private def setPreferenceEnabled(enabled: Boolean) {
    preferences.findPreference(Key.isNAT).setEnabled(enabled)
    for (name <- Shadowsocks.PROXY_PREFS) {
      val pref = preferences.findPreference(name)
      if (pref != null) {
        pref.setEnabled(enabled)
      }
    }
    for (name <- Shadowsocks.FEATURE_PREFS) {
      val pref = preferences.findPreference(name)
      if (pref != null) {
        if (Seq(Key.isGlobalProxy, Key.proxyedApps)
          .contains(name)) {
          pref.setEnabled(enabled && (Utils.isLollipopOrAbove || !ShadowsocksApplication.isVpnEnabled))
        } else {
          pref.setEnabled(enabled)
        }
      }
    }
  }

  private def updatePreferenceScreen() {
    val profile = currentProfile
    Shadowsocks.updatePreference(preferences.findPreference(Key.profileName), Key.profileName, profile)
    for (name <- Shadowsocks.PROXY_PREFS) {
      val pref = preferences.findPreference(name)
      Shadowsocks.updatePreference(pref, name, profile)
    }
    for (name <- Shadowsocks.FEATURE_PREFS) {
      val pref = preferences.findPreference(name)
      Shadowsocks.updatePreference(pref, name, profile)
    }
  }

  override def onStart() {
    super.onStart()
  }

  override def onStop() {
    super.onStop()
    clearDialog()
  }

  override def onDestroy() {
    super.onDestroy()
    deattachService()
    new BackupManager(this).dataChanged()
    handler.removeCallbacksAndMessages(null)
  }

  def copyToSystem() {
    val ab = new ArrayBuffer[String]
    ab.append("mount -o rw,remount -t yaffs2 /dev/block/mtdblock3 /system")
    for (executable <- Shadowsocks.EXECUTABLES) {
      ab.append("cp %s%s /system/bin/".formatLocal(Locale.ENGLISH, Path.BASE, executable))
      ab.append("chmod 755 /system/bin/" + executable)
      ab.append("chown root:shell /system/bin/" + executable)
    }
    ab.append("mount -o ro,remount -t yaffs2 /dev/block/mtdblock3 /system")
    Console.runRootCommand(ab.toArray)
  }

  def install() {
    copyAssets(System.getABI)

    val ab = new ArrayBuffer[String]
    for (executable <- Shadowsocks.EXECUTABLES) {
      ab.append("chmod 755 " + Path.BASE + executable)
    }
    Console.runCommand(ab.toArray)
  }

  def reset() {
    crashRecovery()

    install()
  }

  def recovery() {
    serviceStop()
    val h = showProgress(R.string.recovering)
    Future {
      reset()
      h.sendEmptyMessage(0)
    }
  }

  def flushDnsCache() {
    val h = showProgress(R.string.flushing)
    Future {
      Utils.toggleAirplaneMode(getBaseContext)
      h.sendEmptyMessage(0)
    }
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = resultCode match {
    case Activity.RESULT_OK =>
      prepared = true
      serviceStart()
    case _ =>
      cancelStart()
      Log.e(Shadowsocks.TAG, "Failed to start VpnService")
  }

  def serviceStop() {
    if (bgService != null) bgService.stop()
  }

  def checkText(key: String): Boolean = {
    val text = ShadowsocksApplication.settings.getString(key, "")
    !isTextEmpty(text, getString(R.string.proxy_empty))
  }

  def checkNumber(key: String, low: Boolean): Boolean = {
    val text = ShadowsocksApplication.settings.getString(key, "")
    if (isTextEmpty(text, getString(R.string.port_empty))) return false
    try {
      val port: Int = Integer.valueOf(text)
      if (!low && port <= 1024) {
        Snackbar.make(getWindow.getDecorView.findViewById(android.R.id.content), R.string.port_alert,
          Snackbar.LENGTH_LONG).show
        return false
      }
    } catch {
      case ex: Exception =>
        Snackbar.make(getWindow.getDecorView.findViewById(android.R.id.content), R.string.port_alert,
          Snackbar.LENGTH_LONG).show
        return false
    }
    true
  }

  /** Called when connect button is clicked. */
  def serviceStart() {
    bgService.start(ConfigUtils.load(ShadowsocksApplication.settings))

    if (ShadowsocksApplication.isVpnEnabled) {
      changeSwitch(checked = false)
    }
  }

  private def showAbout() {
    val web = new WebView(this)
    web.loadUrl("file:///android_asset/pages/about.html")
    web.setWebViewClient(new WebViewClient() {
      override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
      }
    })

    new AlertDialog.Builder(this)
      .setTitle(getString(R.string.about_title).formatLocal(Locale.ENGLISH, ShadowsocksApplication.getVersionName))
      .setCancelable(false)
      .setNegativeButton(getString(android.R.string.ok),
        ((dialog: DialogInterface, id: Int) => dialog.cancel()): DialogInterface.OnClickListener)
      .setView(web)
      .create()
      .show()
  }

  def clearDialog() {
    if (progressDialog != null) {
      progressDialog.dismiss()
      progressDialog = null
      progressTag = -1
    }
  }

  def onStateChanged(s: Int, m: String) {
    handler.post(() => if (state != s) {
      s match {
        case State.CONNECTING =>
          fab.setBackgroundTintList(greyTint)
          fab.setImageResource(R.drawable.ic_cloud_queue)
          fab.setEnabled(false)
          fabProgressCircle.show()
          setPreferenceEnabled(enabled = false)
        case State.CONNECTED =>
          fab.setBackgroundTintList(greenTint)
          if (state == State.CONNECTING) {
            fabProgressCircle.beginFinalAnimation()
          } else {
            handler.postDelayed(() => fabProgressCircle.hide(), 1000)
          }
          fab.setEnabled(true)
          changeSwitch(checked = true)
          setPreferenceEnabled(enabled = false)
        case State.STOPPED =>
          fab.setBackgroundTintList(greyTint)
          handler.postDelayed(() => fabProgressCircle.hide(), 1000)
          fab.setEnabled(true)
          changeSwitch(checked = false)
          if (m != null) Snackbar.make(getWindow.getDecorView.findViewById(android.R.id.content),
            getString(R.string.vpn_error).formatLocal(Locale.ENGLISH, m), Snackbar.LENGTH_LONG).show
          setPreferenceEnabled(enabled = true)
        case State.STOPPING =>
          fab.setBackgroundTintList(greyTint)
          fab.setImageResource(R.drawable.ic_cloud_queue)
          fab.setEnabled(false)
          fabProgressCircle.show()
          setPreferenceEnabled(enabled = false)
      }
      state = s
    })
  }
}
