const { app, BrowserWindow, session } = require('electron');
const path = require('path');
const fs = require('fs');

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) { app.quit(); }
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('disable-blink-features', 'AutomationControlled');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36';
app.userAgentFallback = UA;

// Reklame-domæner der blokeres
const AD_HOSTS = [
  'googleadservices.com', 'googlesyndication.com', 'doubleclick.net',
  'adservice.google.com', 'static.doubleclick.net', 'ad.youtube.com',
  'ads.youtube.com', 'yt3.ggpht.com/ytc/AIdro_',
];

// JavaScript der auto-skipper YouTube video-reklamer
const AD_SKIP_JS = `
(function() {
  if (window.__adblock_loaded) return;
  window.__adblock_loaded = true;
  setInterval(() => {
    // Skip-knap (5 sek. reklamer)
    const skip = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
    if (skip) { skip.click(); return; }
    // Hop over video-reklame ved at sætte tid til slutningen
    const ad = document.querySelector('.ad-showing video, .ytp-ad-player-overlay ~ video');
    if (ad && !ad.paused) { ad.currentTime = ad.duration; }
    // Luk overlay-reklamer
    const overlay = document.querySelector('.ytp-ad-overlay-close-button');
    if (overlay) overlay.click();
  }, 300);
})();
`;

let win;

function createWindow() {
  const ses = session.fromPartition('persist:youtube');
  ses.setUserAgent(UA);

  // Bloker reklame-requests
  ses.webRequest.onBeforeRequest((details, callback) => {
    try {
      const host = new URL(details.url).hostname;
      if (AD_HOSTS.some(h => host.endsWith(h))) {
        return callback({ cancel: true });
      }
    } catch (_) {}
    callback({});
  });

  ses.webRequest.onBeforeSendHeaders((details, callback) => {
    details.requestHeaders['User-Agent'] = UA;
    delete details.requestHeaders['X-Requested-With'];
    callback({ requestHeaders: details.requestHeaders });
  });

  win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    title: 'YTube 432Hz 33',
    backgroundColor: '#0f0f0f',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      partition: 'persist:youtube',
    },
  });

  win.setMenuBarVisibility(false);
  win.loadURL('https://www.youtube.com');

  const processorCode = fs.readFileSync(path.join(__dirname, 'pitch-processor.js'), 'utf8');
  const injectCode    = fs.readFileSync(path.join(__dirname, 'inject.js'), 'utf8');
  const combined = `window.__PITCH_CODE__ = ${JSON.stringify(processorCode)};\n${injectCode}\n${AD_SKIP_JS}`;

  win.webContents.on('did-finish-load', () => {
    win.webContents.executeJavaScript(combined).catch(() => {});
  });

  win.webContents.on('page-title-updated', (e, title) => {
    e.preventDefault();
    win.setTitle('YTube 432Hz 33  —  ' + title);
  });
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
