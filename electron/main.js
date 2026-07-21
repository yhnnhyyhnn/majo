const { app, BrowserWindow, dialog } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

let mainWindow;
let backendProcess;

function startBackend() {
  const jarPath = path.join(__dirname, '..', 'backend', 'target', 'majo-backend.jar');
  const fs = require('fs');

  if (!fs.existsSync(jarPath)) {
    console.error('Backend JAR not found at:', jarPath);
    console.error('Run: cd backend && mvn package -DskipTests');
    return null;
  }

  const proc = spawn('java', ['-jar', jarPath], {
    cwd: path.join(__dirname, '..', 'backend'),
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  proc.stdout.on('data', (data) => {
    const text = data.toString();
    console.log('[backend]', text);
  });

  proc.stderr.on('data', (data) => {
    console.error('[backend-err]', data.toString());
  });

  proc.on('error', (err) => {
    console.error('Failed to start backend:', err.message);
    if (mainWindow) {
      dialog.showErrorBox('Backend Error',
        'Failed to start the Java backend.\n\n' +
        'Please ensure Java 21+ is installed.\n' +
        'Run "mvn package -DskipTests" in the backend directory first.\n\n' +
        err.message);
    }
  });

  proc.on('exit', (code) => {
    console.log('Backend exited with code:', code);
    backendProcess = null;
  });

  return proc;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1000,
    height: 720,
    minWidth: 600,
    minHeight: 400,
    title: 'Majo',
    backgroundColor: '#1a1a1a',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
    },
  });

  const isDev = process.argv.includes('--dev');
  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    const distPath = path.join(__dirname, '..', 'frontend', 'dist', 'index.html');
    const fs = require('fs');
    if (fs.existsSync(distPath)) {
      mainWindow.loadFile(distPath);
    } else {
      mainWindow.loadURL('http://localhost:5173');
    }
  }

  mainWindow.on('closed', () => { mainWindow = null; });
}

app.whenReady().then(() => {
  const isDev = process.argv.includes('--dev');
  if (!isDev) {
    backendProcess = startBackend();
  }
  createWindow();
});

app.on('window-all-closed', () => {
  if (backendProcess) {
    backendProcess.kill();
  }
  app.quit();
});

app.on('before-quit', () => {
  if (backendProcess) {
    backendProcess.kill();
  }
});
