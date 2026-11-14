
package com.dishii.soh;
import org.libsdl.app.SDLActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;

import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.os.Build;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;

import android.util.Log;

import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.Executors;
import android.app.AlertDialog;

import android.view.InputDevice;
import android.os.Vibrator;
import android.os.VibrationEffect;

//This class is the main SDLActivity and just sets up a bunch of default files
public class MainActivity extends SDLActivity{

    SharedPreferences preferences;
    private static final CountDownLatch setupLatch = new CountDownLatch(1);
    private volatile boolean mIsAiming = false;
    private static final int COPY_BUFFER_SIZE = 65536;
    private static final int RUMBLE_MAX_DURATION_MS = 5000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i("SoH", "onCreate start");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences("com.dishii.soh.prefs", Context.MODE_PRIVATE);

        Log.i("SoH", "hasStoragePermission=" + hasStoragePermission());

        // Check if storage permissions are granted
        if (hasStoragePermission()) {
            doVersionCheck();
            checkAndSetupFiles();
        } else {
            requestStoragePermission();
        }

        setupControllerOverlay();
        attachController();

        Log.i("SoH", "onCreate complete");
    }

    public static void waitForSetupFromNative() {
        try {
            setupLatch.await();  // Block until setup is complete
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void doVersionCheck(){
        int currentVersion = BuildConfig.VERSION_CODE;
        int storedVersion = preferences.getInt("appVersion", currentVersion);

        if (currentVersion > storedVersion) {
            deleteOutdatedAssets();
            preferences.edit().putInt("appVersion", currentVersion).apply();
        }
    }

    private void deleteOutdatedAssets() {
        File targetRootFolder = new File(Environment.getExternalStorageDirectory(), "SOH");

        File sohFile = new File(targetRootFolder, "soh.otr");
        File ootFile = new File(targetRootFolder, "oot.otr");
        File ootMqFile = new File(targetRootFolder, "oot-mq.otr");
        File sohO2rFile = new File(targetRootFolder, "soh.o2r");
        File ootO2rFile = new File(targetRootFolder, "oot.o2r");
        File ootMqO2rFile = new File(targetRootFolder, "oot-mq.o2r");
        File assetsFolder = new File(targetRootFolder, "assets");

        deleteIfExists(sohFile);
        deleteIfExists(ootFile);
        deleteIfExists(ootMqFile);
        deleteIfExists(sohO2rFile);
        deleteIfExists(ootO2rFile);
        deleteIfExists(ootMqO2rFile);
        deleteRecursiveIfExists(assetsFolder);
    }

    private void deleteIfExists(File file) {
        if (file.exists()) {
            if (file.delete()) {
                Log.i("deleteAssets", "Deleted file: " + file.getAbsolutePath());
            } else {
                Log.w("deleteAssets", "Failed to delete file: " + file.getAbsolutePath());
            }
        } else {
            Log.i("deleteAssets", "File not found (skipped): " + file.getAbsolutePath());
        }
    }

    private void deleteRecursiveIfExists(File dir) {
        if (dir.exists()) {
            deleteRecursive(dir);
            Log.i("deleteAssets", "Deleted directory: " + dir.getAbsolutePath());
        } else {
            Log.i("deleteAssets", "Directory not found (skipped): " + dir.getAbsolutePath());
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }



    // Check if storage permission is granted
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 and above
            return Environment.isExternalStorageManager();
        } else {
            // Android 10 and below
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED;
        }
    }

    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2296;
    private static final int FILE_PICKER_REQUEST_CODE = 0;

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ → MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
            } else {
                // Already granted
                checkAndSetupFiles();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6–10 → request READ/WRITE at runtime
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    STORAGE_PERMISSION_REQUEST_CODE);
        } else {
            // Below Android 6 → permissions granted at install time
            checkAndSetupFiles();
        }
    }

    public void checkAndSetupFiles() {
        File targetRootFolder = new File(Environment.getExternalStorageDirectory(), "SOH");
        File assetsFolder = new File(targetRootFolder, "assets");
        // Support both .otr (9.0.x) and .o2r (9.2.x) archive formats
        File sohOtrFile = new File(targetRootFolder, "soh.o2r");
        File sohOtrFileLegacy = new File(targetRootFolder, "soh.otr");
        // oot.o2r / oot-mq.o2r also count; soh.o2r is not bundled, game can run with just the ROM archive
        File ootO2rFile = new File(targetRootFolder, "oot.o2r");
        File ootMqO2rFile = new File(targetRootFolder, "oot-mq.o2r");

        boolean isMissingAssets = !assetsFolder.exists() || assetsFolder.listFiles() == null || assetsFolder.listFiles().length == 0;
        boolean isMissingSohOtr = !sohOtrFile.exists() && !sohOtrFileLegacy.exists() && !ootO2rFile.exists() && !ootMqO2rFile.exists();

        if (!targetRootFolder.exists() || isMissingAssets || isMissingSohOtr) {
            new AlertDialog.Builder(this)
                    .setTitle("Setup Required")
                    .setMessage("Some required files are missing. The app will create them (~1 minute). Press OK to begin.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> {
                        Executors.newSingleThreadExecutor().execute(() -> {
                            runOnUiThread(() -> Toast.makeText(this, "Setting up files...", Toast.LENGTH_SHORT).show());
                            setupFilesInBackground(targetRootFolder);
                        });
                    })
                    .show();
        } else {
            // No setup needed; but always ensure soh.o2r is present from APK assets
            if (!sohOtrFile.exists()) {
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        try (InputStream in = getAssets().open("soh.o2r");
                             OutputStream out = new FileOutputStream(sohOtrFile)) {
                            byte[] buffer = new byte[COPY_BUFFER_SIZE];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                    } catch (IOException e) {
                        // not bundled, nothing to do
                    }
                    setupLatch.countDown();
                });
            } else {
                setupLatch.countDown();
            }
        }
    }


    private void setupFilesInBackground(File targetRootFolder) {

        File sourceOldRoot = getExternalFilesDir(null);
        File sourceSavesDir = new File(sourceOldRoot, "Save"); // how to tell if there's anything to migrate

        // === Migration from old Android/data/.../files/ directory ===
        if (sourceOldRoot != null && sourceSavesDir.isDirectory()) {
            Log.i("setupFiles", "Migrating old data from: " + sourceOldRoot.getAbsolutePath());

            File[] sourceFiles = sourceOldRoot.listFiles();
            if (sourceFiles != null) {
                for (File file : sourceFiles) {
                    String name = file.getName();
                    if (name.equals("assets") || name.equals("soh.otr") || name.equals("oot-mq.otr") || name.equals("oot.otr") || name.equals("soh.o2r") || name.equals("oot-mq.o2r") || name.equals("oot.o2r")) {
                        continue; // Skip these
                    }

                    File dest = new File(targetRootFolder, name);
                    try {
                        if (file.isDirectory()) {
                            AssetCopyUtil.copyDirectory(file, dest);
                        } else {
                            AssetCopyUtil.copyFile(file, dest);
                        }
                        Log.i("setupFiles", "Migrated: " + name);
                    } catch (IOException e) {
                        Log.e("setupFiles", "Failed to migrate: " + name, e);
                    }
                }
            }

            runOnUiThread(() -> Toast.makeText(this, "Save data migrated", Toast.LENGTH_SHORT).show());
        }

        // Ensure root folder exists
        if (!targetRootFolder.exists()) {
            targetRootFolder.mkdirs();
            if (!targetRootFolder.exists()) {
                Log.e("setupFiles", "Failed to create root folder");
                runOnUiThread(() -> Toast.makeText(this, "Failed to create folder", Toast.LENGTH_LONG).show());
                setupLatch.countDown();
                return;
            }
        }

        // Always ensure mods folder exists
        File targetModsDir = new File(targetRootFolder, "mods");
        if (!targetModsDir.exists()) {
            targetModsDir.mkdirs();
        }

        // Copy assets/ from internal
        File targetAssetsDir = new File(targetRootFolder, "assets");
        try {
            if (!targetAssetsDir.exists()) {
                targetAssetsDir.mkdirs();
            }
            AssetCopyUtil.copyAssetsToExternal(this, "assets", targetAssetsDir.getAbsolutePath());
            runOnUiThread(() -> Toast.makeText(this, "Assets copied", Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error copying assets", Toast.LENGTH_LONG).show());
        }

        // Copy soh.o2r from internal assets if bundled (optional)
        try (InputStream assetIn = getAssets().open("soh.o2r")) {
            File targetOtrFile = new File(targetRootFolder, "soh.o2r");
            targetOtrFile.delete();
            try (OutputStream out = new FileOutputStream(targetOtrFile)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = assetIn.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            runOnUiThread(() -> Toast.makeText(this, "soh.o2r copied", Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            // soh.o2r not bundled in APK assets or copy failed; user must provide their own
        }

        setupLatch.countDown();
    }




    private native void nativeHandleSelectedFile(String filePath);
    private native void nativeDialogResult(int result);

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Handle file selection
            Uri selectedFileUri = data.getData();
            String fileName = "OOT.z64";

            File destinationDirectory = new File(Environment.getExternalStorageDirectory(), "SOH");
            File destinationFile = new File(destinationDirectory, fileName);

            if (selectedFileUri != null) {
                destinationFile.delete();
                try (InputStream in = getContentResolver().openInputStream(selectedFileUri);
                     OutputStream out = new FileOutputStream(destinationFile)) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (destinationFile.exists() && destinationFile.length() > 0) {
                nativeHandleSelectedFile(destinationFile.getPath());
            } else {
                runOnUiThread(() -> Toast.makeText(this, "Failed to copy ROM file", Toast.LENGTH_LONG).show());
                nativeHandleSelectedFile(null);
            }

        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            // Handle MANAGE_EXTERNAL_STORAGE result
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    checkAndSetupFiles();
                } else {
                    Toast.makeText(this, "Storage permission is required to access files.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    public void showAlertDialog(String title, String message, String btn1, String btn2) {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(btn1, (dialog, which) -> nativeDialogResult(0));
            if (btn2 != null && !btn2.isEmpty()) {
                builder.setNegativeButton(btn2, (dialog, which) -> nativeDialogResult(1));
            }
            AlertDialog dialog = builder.create();
            // FLAG_NOT_FOCUSABLE prevents stealing SDL window focus so onWindowFocusChanged
            // is never called — SDL never fires FOCUS_LOST, ImGui keeps gamepad state intact,
            // SELECT/BACK menu toggle continues to work after dialog dismissal.
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            dialog.show();
        });
    }

    public void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        runOnUiThread(() -> startActivityForResult(intent, 0));
    }

    // Check if external storage is available and writable
    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public native void attachController();
    public native void detachController();
    // Native method for setting button state
    public native void setButton(int button, boolean value);
    public native void setCameraState(int axis, float value);
    private native void setItemButtonPulse();
    private native void setItemButtonHeld(boolean held);

    // Native method for setting joystick axis value
    public native void setAxis(int axis, short value);

    // Signals C++ that the gamepad BACK/SELECT button was pressed, bypassing SDL.
    public native void nativeGamepadBackPressed();
    // Injects a directional menu nav key: dir 0=up 1=down 2=left 3=right.
    public native void nativeMenuNavKey(int dir, boolean pressed);

    public void SetFirstPersonAimingActive(boolean active) {
        mIsAiming = active;
    }

    @Override
    protected void onPause() {
        super.onPause();
        setItemButtonHeld(false);
        mIsAiming = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int keyCode = event.getKeyCode();
            // SDL loses controller tracking after activity transitions; catch BACK at the activity level.
            boolean isGamepad = (event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) != 0
                             || (event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != 0;
            if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                    (keyCode == KeyEvent.KEYCODE_BACK && isGamepad)) {
                nativeGamepadBackPressed();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private Button button1, button2, button3, button4;
    private Button buttonA, buttonB, buttonX, buttonY;
    private Button buttonDpadUp, buttonDpadDown, buttonDpadLeft, buttonDpadRight;
    private Button buttonLB, buttonRB, buttonZ, buttonStart, buttonBack;
    private Button buttonToggle;
    private FrameLayout leftJoystick;
    private ImageView leftJoystickKnob;
    private View overlayView;
    private boolean controlEditMode = false;
    private final java.util.ArrayList<View> editableControls = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> editableControlNames = new java.util.ArrayList<>();

    // Function to set up the controller overlay (inflate layout and initialize buttons)
    private void setupControllerOverlay() {
        // Inflate the touchcontrol_overlay layout
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.touchcontrol_overlay, null);

        // Set layout params for overlayView to control positioning and sizing
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        overlayView.setLayoutParams(layoutParams);
        // Add overlay view to the main layout (you may need to add it to a container like FrameLayout)
        ViewGroup view = (ViewGroup) findViewById(android.R.id.content);
        view.addView(overlayView);
        view.setKeepScreenOn(true);

        final ViewGroup buttonGroup = overlayView.findViewById(R.id.button_group);

        buttonA = overlayView.findViewById(R.id.buttonA);
        buttonB = overlayView.findViewById(R.id.buttonB);
        buttonX = overlayView.findViewById(R.id.buttonX);
        buttonY = overlayView.findViewById(R.id.buttonY);

        buttonDpadUp = overlayView.findViewById(R.id.buttonDpadUp);
        buttonDpadDown = overlayView.findViewById(R.id.buttonDpadDown);
        buttonDpadLeft = overlayView.findViewById(R.id.buttonDpadLeft);
        buttonDpadRight = overlayView.findViewById(R.id.buttonDpadRight);

        buttonLB = overlayView.findViewById(R.id.buttonLB);
        buttonRB = overlayView.findViewById(R.id.buttonRB);
        buttonZ = overlayView.findViewById(R.id.buttonZ);

        buttonStart = overlayView.findViewById(R.id.buttonStart);
        buttonBack = overlayView.findViewById(R.id.buttonBack);

        buttonToggle = overlayView.findViewById(R.id.buttonToggle);

        // Initialize joysticks and joystick knobs from the inflated layout
        leftJoystick = overlayView.findViewById(R.id.left_joystick);
        leftJoystickKnob = overlayView.findViewById(R.id.left_joystick_knob);

        FrameLayout rightScreenArea = overlayView.findViewById(R.id.right_screen_area);
        // Keep the look-around touch surface underneath every movable control.
        // It originally followed the joystick in the child order and could cover it
        // after the joystick was translated into the right-side area.
        buttonGroup.removeView(rightScreenArea);
        buttonGroup.addView(rightScreenArea, 0);

        addTouchListener(buttonA, ControllerButtons.BUTTON_A);
        addTouchListener(buttonB, ControllerButtons.BUTTON_B);
        addTouchListener(buttonX, ControllerButtons.BUTTON_X);
        addTouchListener(buttonY, ControllerButtons.BUTTON_Y);

        setupCButtons(buttonDpadUp, ControllerButtons.BUTTON_DPAD_UP);
        setupCButtons(buttonDpadDown, ControllerButtons.BUTTON_DPAD_DOWN);
        setupCButtons(buttonDpadLeft, ControllerButtons.BUTTON_DPAD_LEFT);
        setupCButtons(buttonDpadRight, ControllerButtons.BUTTON_DPAD_RIGHT);

        addTouchListener(buttonLB, ControllerButtons.BUTTON_LB);
        addTouchListener(buttonRB, ControllerButtons.BUTTON_RB);
        addTouchListener(buttonZ, ControllerButtons.AXIS_RT);

        addTouchListener(buttonStart, ControllerButtons.BUTTON_START);
        // BACK uses nativeGamepadBackPressed directly; setButton(BUTTON_BACK) feeds ImGuiKey_GamepadBack, unreliable when SDL Gamepads list is empty.
        buttonBack.setOnTouchListener((v, event) -> handleSlidingButtonTouch(v, event));


        setupJoystick(leftJoystick, leftJoystickKnob, true);

        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightUp), 0, -1);
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightDown), 0, 1);
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightLeft), -1, 0);
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightRight), 1, 0);

        setupLookAround(rightScreenArea);

        // Las zonas vacías de la capa también deben permitir interacción con SDL.
        buttonGroup.setOnTouchListener((v, event) -> {
            forwardTouchToSDL(event);
            return true;
        });

        setupToggleButton(buttonToggle,buttonGroup);
        setupTouchControlEditor(buttonGroup, rightScreenArea);

        // Exclude Back/Start from gesture nav zones (they sit at screen edges in landscape).
        // Must be called on each button in its own local coordinates.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ViewTreeObserver.OnGlobalLayoutListener gestureListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    overlayView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    Rect selfRect = new Rect(0, 0, buttonBack.getWidth(), buttonBack.getHeight());
                    buttonBack.setSystemGestureExclusionRects(Arrays.asList(selfRect));
                    selfRect = new Rect(0, 0, buttonStart.getWidth(), buttonStart.getHeight());
                    buttonStart.setSystemGestureExclusionRects(Arrays.asList(selfRect));
                }
            };
            overlayView.getViewTreeObserver().addOnGlobalLayoutListener(gestureListener);
        }

    }

    // Reenvía una copia del toque a la superficie SDL que está debajo de la capa
    // Android. Así los menús ImGui de configuración reciben toques directos aunque
    // los controles virtuales sigan visibles. Los controles continúan procesando
    // el mismo evento para no perder joystick, cámara ni botones.
    private void forwardTouchToSDL(MotionEvent event) {
        if (event == null || controlEditMode || SDLActivity.mSurface == null) return;
        MotionEvent forwarded = MotionEvent.obtain(event);
        int[] surfaceLocation = new int[2];
        SDLActivity.mSurface.getLocationOnScreen(surfaceLocation);
        forwarded.offsetLocation(-surfaceLocation[0], -surfaceLocation[1]);
        SDLActivity.mSurface.dispatchTouchEvent(forwarded);
        forwarded.recycle();
    }

    // Touch layout editor: drag controls, then adjust their scale and opacity.
    private void setupTouchControlEditor(ViewGroup group, FrameLayout rightScreenArea) {
        int[] ids = { R.id.left_joystick, R.id.buttonLB, R.id.buttonRB, R.id.buttonZ,
                R.id.buttonDpadUp, R.id.buttonDpadDown, R.id.buttonDpadLeft, R.id.buttonDpadRight,
                R.id.buttonA, R.id.buttonB, R.id.buttonX, R.id.buttonY,
                R.id.buttonStart, R.id.buttonBack, R.id.buttonCRightUp, R.id.buttonCRightDown,
                R.id.buttonCRightLeft, R.id.buttonCRightRight };
        String[] names = { "Joystick izquierdo", "L", "R", "Z", "C arriba", "C abajo",
                "C izquierda", "C derecha", "A", "B", "X", "Y", "Start", "Back",
                "C derecho arriba", "C derecho abajo", "C derecho izquierda", "C derecho derecha" };
        for (int i = 0; i < ids.length; i++) {
            View control = overlayView.findViewById(ids[i]);
            if (control != null) {
                editableControls.add(control);
                editableControlNames.add(names[i]);
                restoreControlTransform(control, i);
            }
        }
        // Re-apply transforms after layout: the C-arrow container now spans the
        // whole overlay, so their default positions are calculated from screen size.
        overlayView.post(() -> {
            for (int i = 0; i < editableControls.size(); i++) {
                restoreControlTransform(editableControls.get(i), i);
            }
        });
        Button edit = overlayView.findViewById(R.id.buttonEditControls);
        edit.setOnClickListener(v -> {
            controlEditMode = !controlEditMode;
            edit.setText(controlEditMode ? "Guardar controles" : "Editar controles");
            if (controlEditMode) {
                rightScreenArea.setVisibility(View.GONE);
                for (int i = 0; i < editableControls.size(); i++) installEditorTouch(editableControls.get(i), i);
                Toast.makeText(this, "Arrastra los controles. Toca uno para ajustar tamaño y opacidad.", Toast.LENGTH_LONG).show();
            } else {
                for (int i = 0; i < editableControls.size(); i++) saveControlTransform(editableControls.get(i), i);
                restoreControlTouchListeners(rightScreenArea);
                rightScreenArea.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Distribución guardada", Toast.LENGTH_SHORT).show();
            }
        });
        edit.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Controles táctiles")
                    .setMessage("Restablecerá la posición, tamaño y opacidad de todos los controles.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Restablecer", (d, w) -> {
                        for (int i = 0; i < editableControls.size(); i++) {
                            View c = editableControls.get(i);
                            for (View member : getControlTransformViews(c, i)) {
                                member.setTranslationX(0); member.setTranslationY(0);
                                member.setScaleX(1); member.setScaleY(1); member.setAlpha(1);
                            }
                            preferences.edit().remove(controlKey(transformPreferenceIndex(i), "x"))
                                    .remove(controlKey(transformPreferenceIndex(i), "y"))
                                    .remove(controlKey(transformPreferenceIndex(i), "s"))
                                    .remove(controlKey(transformPreferenceIndex(i), "a")).apply();
                        }
                    }).show();
            return true;
        });
    }

    private String controlKey(int index, String suffix) { return "touch_editor_" + index + "_" + suffix; }
    private java.util.List<View> getControlTransformViews(View control, int index) {
        // The four C-button hit targets and their decorative cross are one logical control.
        if (index >= 4 && index <= 7) {
            java.util.ArrayList<View> group = new java.util.ArrayList<>();
            View cross = overlayView.findViewById(R.id.imageView);
            if (cross != null) group.add(cross);
            if (buttonDpadUp != null) group.add(buttonDpadUp);
            if (buttonDpadDown != null) group.add(buttonDpadDown);
            if (buttonDpadLeft != null) group.add(buttonDpadLeft);
            if (buttonDpadRight != null) group.add(buttonDpadRight);
            return group;
        }
        return java.util.Collections.singletonList(control);
    }
    private int transformPreferenceIndex(int index) {
        // Keep the existing preference slots for all other controls; C group uses slot 4.
        return (index >= 4 && index <= 7) ? 4 : index;
    }
    private void restoreControlTransform(View v, int i) {
        int keyIndex = transformPreferenceIndex(i);
        float x = preferences.getFloat(controlKey(keyIndex, "x"), 0);
        float y = preferences.getFloat(controlKey(keyIndex, "y"), 0);
        // Default C-arrow positions reproduce the former 150dp cluster, but
        // are offsets from the full-screen parent center rather than a small box.
        if (!preferences.contains(controlKey(keyIndex, "x")) || !preferences.contains(controlKey(keyIndex, "y"))) {
            float density = getResources().getDisplayMetrics().density;
            int width = overlayView != null ? overlayView.getWidth() : 0;
            if (width > 0) {
                int id = v.getId();
                if (id == R.id.buttonCRightUp || id == R.id.buttonCRightDown) x = width / 2f - 200f * density;
                else if (id == R.id.buttonCRightLeft) x = width / 2f - 252f * density;
                else if (id == R.id.buttonCRightRight) x = width / 2f - 148f * density;
                if (id == R.id.buttonCRightUp) y = -52f * density;
                else if (id == R.id.buttonCRightDown) y = 52f * density;
                else y = 0f;
            }
        }
        float scale = preferences.getFloat(controlKey(keyIndex, "s"), 1f);
        float alpha = preferences.getFloat(controlKey(keyIndex, "a"), 1f);
        for (View member : getControlTransformViews(v, i)) {
            member.setTranslationX(x); member.setTranslationY(y);
            member.setScaleX(scale); member.setScaleY(scale); member.setAlpha(alpha);
        }
    }
    private void saveControlTransform(View v, int i) {
        int keyIndex = transformPreferenceIndex(i);
        preferences.edit().putFloat(controlKey(keyIndex, "x"), v.getTranslationX())
                .putFloat(controlKey(keyIndex, "y"), v.getTranslationY())
                .putFloat(controlKey(keyIndex, "s"), v.getScaleX())
                .putFloat(controlKey(keyIndex, "a"), v.getAlpha()).apply();
    }
    private void installEditorTouch(View v, int index) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, lastRawX, lastRawY;
            boolean moved;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        lastRawX = downRawX; lastRawY = downRawY; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                        if (Math.abs(dx) + Math.abs(dy) > 8) moved = true;
                        float stepX = e.getRawX() - lastRawX;
                        float stepY = e.getRawY() - lastRawY;
                        for (View member : getControlTransformViews(view, index)) {
                            member.setTranslationX(member.getTranslationX() + stepX);
                            member.setTranslationY(member.getTranslationY() + stepY);
                        }
                        lastRawX = e.getRawX(); lastRawY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) showControlAdjustDialog(view, index);
                        saveControlTransform(view, index);
                        return true;
                }
                return true;
            }
        });
    }
    private void showControlAdjustDialog(View control, int index) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 12, 32, 4);
        TextView sizeLabel = new TextView(this); TextView alphaLabel = new TextView(this);
        SeekBar size = new SeekBar(this); size.setMax(150);
        SeekBar alpha = new SeekBar(this); alpha.setMax(100);
        size.setProgress(Math.max(0, Math.min(150, Math.round((control.getScaleX() - 0.5f) * 100))));
        alpha.setProgress(Math.round(control.getAlpha() * 100));
        sizeLabel.setText("Tamaño: " + Math.round(control.getScaleX() * 100) + "%");
        alphaLabel.setText("Opacidad: " + Math.round(control.getAlpha() * 100) + "%");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { float s = 0.5f + p / 100f; for (View member : getControlTransformViews(control, index)) { member.setScaleX(s); member.setScaleY(s); } sizeLabel.setText("Tamaño: " + Math.round(s * 100) + "%"); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
        });
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { for (View member : getControlTransformViews(control, index)) member.setAlpha(p / 100f); alphaLabel.setText("Opacidad: " + p + "%"); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
        });
        layout.addView(sizeLabel); layout.addView(size); layout.addView(alphaLabel); layout.addView(alpha);
        new AlertDialog.Builder(this).setTitle(editableControlNames.get(index)).setView(layout)
                .setPositiveButton("Aceptar", (d, w) -> saveControlTransform(control, index)).show();
    }
    private void restoreControlTouchListeners(FrameLayout rightScreenArea) {
        addTouchListener(buttonA, ControllerButtons.BUTTON_A); addTouchListener(buttonB, ControllerButtons.BUTTON_B);
        addTouchListener(buttonX, ControllerButtons.BUTTON_X); addTouchListener(buttonY, ControllerButtons.BUTTON_Y);
        setupCButtons(buttonDpadUp, ControllerButtons.BUTTON_DPAD_UP); setupCButtons(buttonDpadDown, ControllerButtons.BUTTON_DPAD_DOWN);
        setupCButtons(buttonDpadLeft, ControllerButtons.BUTTON_DPAD_LEFT); setupCButtons(buttonDpadRight, ControllerButtons.BUTTON_DPAD_RIGHT);
        addTouchListener(buttonLB, ControllerButtons.BUTTON_LB); addTouchListener(buttonRB, ControllerButtons.BUTTON_RB);
        addTouchListener(buttonZ, ControllerButtons.AXIS_RT); addTouchListener(buttonStart, ControllerButtons.BUTTON_START);
        buttonBack.setOnTouchListener((v, event) -> handleSlidingButtonTouch(v, event));
        setupJoystick(leftJoystick, leftJoystickKnob, true);
        // El botón Mostrar/Ocultar conserva su clic normal. El editor había
        // instalado un OnTouchListener sobre él y no lo retiraba al guardar,
        // por lo que seguía interceptando los toques fuera del modo edición.
        if (buttonToggle != null) {
            buttonToggle.setOnTouchListener(null);
            buttonToggle.setPressed(false);
        }
        // Reinstala las cuatro flechas C después de salir del editor.
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightUp), 0, -1);
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightDown), 0, 1);
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightLeft), -1, 0);
        setupCArrowButton(overlayView.findViewById(R.id.buttonCRightRight), 1, 0);
        setupLookAround(rightScreenArea);
    }

    private void setupToggleButton(Button button, ViewGroup uiGroup){
        // No instalar un listener consumidor en la vista raíz: los toques de fondo
        // se reenvían a SDL para que el menú de configuración siga siendo táctil.
        boolean isHidden = preferences.getBoolean("controlsVisible", false); // Default to 'false' (visible)
        uiGroup.setVisibility(isHidden ? View.INVISIBLE : View.VISIBLE);
        if (isHidden) {
            DisableTouchArea();
            overlayView.setOnTouchListener(null);
        } else {
            EnableTouchArea();
            overlayView.setOnTouchListener(null);
        }
        boolean toggleVisible = preferences.getBoolean("toggleButtonVisible", true); // Default to visible
        button.setVisibility(toggleVisible ? View.VISIBLE : View.GONE);
        button.setOnClickListener(v -> {
            boolean currentlyHidden = uiGroup.getVisibility() != View.VISIBLE;
            if (currentlyHidden) {
                uiGroup.setVisibility(View.VISIBLE);
                EnableTouchArea();
                overlayView.setOnTouchListener(null);
            } else {
                uiGroup.setVisibility(View.INVISIBLE);
                DisableTouchArea();
                overlayView.setOnTouchListener(null);
            }
            preferences.edit().putBoolean("controlsVisible", !currentlyHidden).apply();
        });
    }

    // Multi-touch routing: each finger owns an independent active control.
    // A finger may slide between controls while other fingers keep their buttons held.
    private final Map<Integer, View> slidingActiveButtons = new HashMap<>();
    private final Map<View, Integer> slidingButtonPressCounts = new HashMap<>();

    private boolean handleSlidingButtonTouch(View source, MotionEvent event) {
        forwardTouchToSDL(event);
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int pointerId = event.getPointerId(actionIndex);
            float rawX = event.getRawX() + (event.getX(actionIndex) - event.getX(0));
            float rawY = event.getRawY() + (event.getY(actionIndex) - event.getY(0));
            updateSlidingPointer(pointerId, rawX, rawY);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            // A split touch stream can contain pointers owned by other controls.
            // Only move fingers that this button router originally claimed.
            for (int i = 0; i < event.getPointerCount(); i++) {
                int pointerId = event.getPointerId(i);
                if (!slidingActiveButtons.containsKey(pointerId)) continue;
                updateSlidingPointer(pointerId,
                        event.getRawX() + (event.getX(i) - event.getX(0)),
                        event.getRawY() + (event.getY(i) - event.getY(0)));
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            releaseSlidingPointer(event.getPointerId(actionIndex));
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            // Android may cancel one split touch stream; release only pointers in this event.
            for (int i = 0; i < event.getPointerCount(); i++) releaseSlidingPointer(event.getPointerId(i));
            return true;
        }
        return true;
    }

    private void updateSlidingPointer(int pointerId, float rawX, float rawY) {
        View target = findSlidingButtonAt(rawX, rawY);
        View previous = slidingActiveButtons.get(pointerId);
        if (target == previous) return;
        if (previous != null) releaseSlidingButtonForPointer(previous);
        if (target == null) slidingActiveButtons.remove(pointerId);
        else {
            slidingActiveButtons.put(pointerId, target);
            pressSlidingButtonForPointer(target);
        }
    }

    private void releaseSlidingPointer(int pointerId) {
        View previous = slidingActiveButtons.remove(pointerId);
        if (previous != null) releaseSlidingButtonForPointer(previous);
    }

    private void pressSlidingButtonForPointer(View button) {
        int count = slidingButtonPressCounts.containsKey(button) ? slidingButtonPressCounts.get(button) : 0;
        slidingButtonPressCounts.put(button, count + 1);
        if (count == 0) pressSlidingButton(button);
    }

    private void releaseSlidingButtonForPointer(View button) {
        Integer count = slidingButtonPressCounts.get(button);
        if (count == null) return;
        if (count <= 1) {
            slidingButtonPressCounts.remove(button);
            releaseSlidingButton(button);
        } else {
            slidingButtonPressCounts.put(button, count - 1);
        }
    }

    private View findSlidingButtonAt(float rawX, float rawY) {
        int[] ids = { R.id.buttonA, R.id.buttonB, R.id.buttonX, R.id.buttonY,
                R.id.buttonLB, R.id.buttonRB, R.id.buttonZ, R.id.buttonStart, R.id.buttonBack,
                R.id.buttonDpadUp, R.id.buttonDpadDown, R.id.buttonDpadLeft, R.id.buttonDpadRight,
                R.id.buttonCRightUp, R.id.buttonCRightDown, R.id.buttonCRightLeft, R.id.buttonCRightRight };
        // Reverse order matches Android's visual hit-test order for overlapping controls.
        for (int i = ids.length - 1; i >= 0; i--) {
            View candidate = overlayView.findViewById(ids[i]);
            if (candidate == null || candidate.getVisibility() != View.VISIBLE || candidate.getAlpha() <= 0.01f) continue;
            Rect bounds = new Rect();
            if (candidate.getGlobalVisibleRect(bounds) && bounds.contains((int) rawX, (int) rawY)) return candidate;
        }
        return null;
    }

    private void pressSlidingButton(View v) {
        if (v == null) return;
        int id = v.getId();
        v.setPressed(true);
        if (id == R.id.buttonA) { setButton(ControllerButtons.BUTTON_A, true); nativeMenuNavKey(4, true); }
        else if (id == R.id.buttonB) { setButton(ControllerButtons.BUTTON_B, true); nativeMenuNavKey(5, true); }
        else if (id == R.id.buttonX) setButton(ControllerButtons.BUTTON_X, true);
        else if (id == R.id.buttonY) setButton(ControllerButtons.BUTTON_Y, true);
        else if (id == R.id.buttonLB) setButton(ControllerButtons.BUTTON_LB, true);
        else if (id == R.id.buttonRB) setButton(ControllerButtons.BUTTON_RB, true);
        else if (id == R.id.buttonZ) setButton(ControllerButtons.AXIS_RT, true);
        else if (id == R.id.buttonStart) setButton(ControllerButtons.BUTTON_START, true);
        else if (id == R.id.buttonBack) nativeGamepadBackPressed();
        else if (id == R.id.buttonDpadUp || id == R.id.buttonDpadDown || id == R.id.buttonDpadLeft || id == R.id.buttonDpadRight) {
            int b = id == R.id.buttonDpadUp ? ControllerButtons.BUTTON_DPAD_UP : id == R.id.buttonDpadDown ? ControllerButtons.BUTTON_DPAD_DOWN : id == R.id.buttonDpadLeft ? ControllerButtons.BUTTON_DPAD_LEFT : ControllerButtons.BUTTON_DPAD_RIGHT;
            setButton(b, true); nativeMenuNavKey(b - ControllerButtons.BUTTON_DPAD_UP, true);
        } else if (id == R.id.buttonCRightUp) setAxis(ControllerButtons.AXIS_RY, (short)-Short.MAX_VALUE);
        else if (id == R.id.buttonCRightDown) setAxis(ControllerButtons.AXIS_RY, (short)Short.MAX_VALUE);
        else if (id == R.id.buttonCRightLeft) setAxis(ControllerButtons.AXIS_RX, (short)-Short.MAX_VALUE);
        else if (id == R.id.buttonCRightRight) setAxis(ControllerButtons.AXIS_RX, (short)Short.MAX_VALUE);
    }

    private void releaseSlidingButton(View v) {
        if (v == null) return;
        int id = v.getId();
        v.setPressed(false);
        if (id == R.id.buttonA) { setButton(ControllerButtons.BUTTON_A, false); nativeMenuNavKey(4, false); }
        else if (id == R.id.buttonB) { setButton(ControllerButtons.BUTTON_B, false); nativeMenuNavKey(5, false); }
        else if (id == R.id.buttonX) setButton(ControllerButtons.BUTTON_X, false);
        else if (id == R.id.buttonY) setButton(ControllerButtons.BUTTON_Y, false);
        else if (id == R.id.buttonLB) setButton(ControllerButtons.BUTTON_LB, false);
        else if (id == R.id.buttonRB) setButton(ControllerButtons.BUTTON_RB, false);
        else if (id == R.id.buttonZ) setButton(ControllerButtons.AXIS_RT, false);
        else if (id == R.id.buttonStart) setButton(ControllerButtons.BUTTON_START, false);
        else if (id == R.id.buttonDpadUp || id == R.id.buttonDpadDown || id == R.id.buttonDpadLeft || id == R.id.buttonDpadRight) {
            int b = id == R.id.buttonDpadUp ? ControllerButtons.BUTTON_DPAD_UP : id == R.id.buttonDpadDown ? ControllerButtons.BUTTON_DPAD_DOWN : id == R.id.buttonDpadLeft ? ControllerButtons.BUTTON_DPAD_LEFT : ControllerButtons.BUTTON_DPAD_RIGHT;
            setButton(b, false); nativeMenuNavKey(b - ControllerButtons.BUTTON_DPAD_UP, false);
        } else if (id == R.id.buttonCRightUp || id == R.id.buttonCRightDown) setAxis(ControllerButtons.AXIS_RY, (short)0);
        else if (id == R.id.buttonCRightLeft || id == R.id.buttonCRightRight) setAxis(ControllerButtons.AXIS_RX, (short)0);
    }

    private void addTouchListener(Button button, int buttonNum) {
        if (button != null) button.setOnTouchListener((v, event) -> handleSlidingButtonTouch(v, event));
    }

    private void setupCButtons(Button button, int dpadButton) {
        if (button != null) button.setOnTouchListener((v, event) -> handleSlidingButtonTouch(v, event));
    }

    boolean TouchAreaEnabled = true;

    void DisableTouchArea(){
        TouchAreaEnabled = false;
    }
    void EnableTouchArea(){
        TouchAreaEnabled = true;
    }

    void SetToggleButtonVisible(boolean visible) {
        runOnUiThread(() -> {
            if (buttonToggle != null) {
                buttonToggle.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
            preferences.edit().putBoolean("toggleButtonVisible", visible).apply();
        });
    }

    private void setupLookAround(FrameLayout rightScreenArea) {
        rightScreenArea.setOnTouchListener(new View.OnTouchListener() {
            private float lastX = 0;
            private float lastY = 0;
            private boolean isTouching = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                forwardTouchToSDL(event);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Start tracking the finger's position
                        lastX = event.getX();
                        lastY = event.getY();
                        isTouching = true;
                        if (mIsAiming && TouchAreaEnabled) {
                            setItemButtonPulse();
                            setItemButtonHeld(true);
                        }
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (isTouching) {
                            // Calculate the change in position (delta)
                            float deltaX = event.getX() - lastX;
                            float deltaY = event.getY() - lastY;

                            // Update the last position
                            lastX = event.getX();
                            lastY = event.getY();

                            // Increase sensitivity by using a larger multiplier
                            // Adjust these multipliers to suit your needs
                            float sensitivityMultiplier = 15; // Higher value for more sensitivity
                            float rx = (deltaX * sensitivityMultiplier);
                            float ry = (deltaY * sensitivityMultiplier);

                            // Send the mapped values to the joystick axes
                            setCameraState(0, rx); // Right stick X axis
                            setCameraState(1, ry); // Right stick Y axis
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Stop tracking the finger's position and reset joystick input
                        isTouching = false;
                        setCameraState(0, 0.0f); // Reset right stick X axis
                        setCameraState(1, 0.0f); // Reset right stick Y axis
                        if (mIsAiming && TouchAreaEnabled) {
                            setItemButtonHeld(false);
                        }
                        break;
                }
                return TouchAreaEnabled; // Event full handled
            }
        });
    }





    // C-right arrows participate in the same slide-between-buttons gesture router.
    private void setupCArrowButton(View button, final int directionX, final int directionY) {
        if (button != null) button.setOnTouchListener((v, event) -> handleSlidingButtonTouch(v, event));
    }

    // Function to set joystick movement with reset to center when not touched
    private void setupJoystick(FrameLayout joystickLayout, ImageView joystickKnob, boolean isLeft) {
        joystickLayout.post(() -> {
            final float joystickCenterX = joystickLayout.getWidth() / 2f;
            final float joystickCenterY = joystickLayout.getHeight() / 2f;

            joystickLayout.setOnTouchListener(new View.OnTouchListener() {
                private int activePointerId = MotionEvent.INVALID_POINTER_ID;

                private void resetJoystick() {
                    joystickKnob.setX(joystickCenterX - joystickKnob.getWidth() / 2f);
                    joystickKnob.setY(joystickCenterY - joystickKnob.getHeight() / 2f);
                    setAxis(isLeft ? ControllerButtons.AXIS_LX : ControllerButtons.AXIS_RX, (short) 0);
                    setAxis(isLeft ? ControllerButtons.AXIS_LY : ControllerButtons.AXIS_RY, (short) 0);
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                }

                private void updateJoystick(MotionEvent event, int pointerIndex) {
                    float deltaX = event.getX(pointerIndex) - joystickCenterX;
                    float deltaY = event.getY(pointerIndex) - joystickCenterY;
                    float maxRadius = Math.max(1f, joystickLayout.getWidth() / 2f - joystickKnob.getWidth() / 2f);
                    float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    if (distance > maxRadius) {
                        float scale = maxRadius / distance;
                        deltaX *= scale;
                        deltaY *= scale;
                    }
                    joystickKnob.setX(joystickCenterX + deltaX - joystickKnob.getWidth() / 2f);
                    joystickKnob.setY(joystickCenterY + deltaY - joystickKnob.getHeight() / 2f);
                    setAxis(isLeft ? ControllerButtons.AXIS_LX : ControllerButtons.AXIS_RX,
                            (short) (deltaX / maxRadius * Short.MAX_VALUE));
                    setAxis(isLeft ? ControllerButtons.AXIS_LY : ControllerButtons.AXIS_RY,
                            (short) (deltaY / maxRadius * Short.MAX_VALUE));
                }

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    forwardTouchToSDL(event);
                    int action = event.getActionMasked();
                    int index = event.getActionIndex();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                            activePointerId = event.getPointerId(index);
                            updateJoystick(event, index);
                        }
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                            int pointerIndex = event.findPointerIndex(activePointerId);
                            if (pointerIndex >= 0) updateJoystick(event, pointerIndex);
                        }
                    } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                        if (event.getPointerId(index) == activePointerId) resetJoystick();
                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        resetJoystick();
                    }
                    return true;
                }
            });
        });
    }

    public void startRumble(int lowIntensity, int highIntensity) {
        int amplitude = Math.max(lowIntensity, highIntensity);
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
                (sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) continue;
            Vibrator dv = device.getVibrator();
            if (dv != null && dv.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dv.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_DURATION_MS, amplitude > 0 ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    dv.vibrate(RUMBLE_MAX_DURATION_MS);
                }
                return;
            }
        }
        Vibrator sv = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (sv != null && sv.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sv.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_DURATION_MS, amplitude > 0 ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                sv.vibrate(RUMBLE_MAX_DURATION_MS);
            }
        }
    }

    public void stopRumble() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
                (sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) continue;
            Vibrator dv = device.getVibrator();
            if (dv != null && dv.hasVibrator()) dv.cancel();
        }
        Vibrator sv = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (sv != null) sv.cancel();
    }

}
