package com.echolink.mobile.fixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A separate application so instrumentation never force-stops Manuscript. */
public final class S23ExternalEditorFixtureActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_EMPTY = "empty";
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_MULTILINE = "multiline";
    public static final String MODE_PASSWORD = "password";
    public static final String MODE_TRANSIENT = "transient";
    public static final String MODE_DETACHED = "detached";
    public static final String MODE_TARGET_LOSS = "target-loss";
    public static final String MODE_PACKAGE_SWITCH = "package-switch";
    public static final String MODE_STRUCTURAL_FIELDS = "structural-fields";
    public static final String STRUCTURAL_FIELD_ONE_DESCRIPTION = "Generated structural field one";
    public static final String STRUCTURAL_FIELD_TWO_DESCRIPTION = "Generated structural field two";
    public static final String FIXTURE_ROOT_DESCRIPTION = "Manuscript external field fixture";
    public static final String FINISH_DESCRIPTION = "Finish external field fixture";
    public static final String EDITOR_DESCRIPTION_PREFIX = "Generated fixture editor: ";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String requestedMode = getIntent().getStringExtra(EXTRA_MODE);
        final String mode = requestedMode == null ? "" : requestedMode;
        EditText editor = editorFor(mode);
        EditText second = null;
        if (MODE_STRUCTURAL_FIELDS.equals(mode)) {
            editor.setId(android.R.id.input);
            editor.setContentDescription(STRUCTURAL_FIELD_ONE_DESCRIPTION);
            second = editorFor(MODE_NORMAL);
            second.setId(android.R.id.input);
            second.setContentDescription(STRUCTURAL_FIELD_TWO_DESCRIPTION);
            second.setText("Second generated field.");
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setContentDescription(FIXTURE_ROOT_DESCRIPTION);
        root.setPadding(dp(20), dp(30), dp(20), dp(20));
        TextView heading = new TextView(this);
        heading.setText("Manuscript generated field fixture");
        heading.setTextSize(18f);
        root.addView(heading);
        root.addView(editor, params());
        if (second != null) root.addView(second, params());
        if (FixtureActionContract.INSTANCE.isAvailable(mode) || second != null) {
            Button action = new Button(this);
            action.setText(actionDescription(mode));
            action.setContentDescription(action.getText());
            EditText secondEditor = second;
            action.setOnClickListener(view -> {
                if (MODE_DETACHED.equals(mode)) ((ViewGroup) editor.getParent()).removeView(editor);
                else if (MODE_TARGET_LOSS.equals(mode)) { editor.clearFocus(); view.requestFocus(); }
                else if (MODE_PACKAGE_SWITCH.equals(mode)) startActivity(new Intent(Settings.ACTION_SETTINGS));
                else if (FixtureActionContract.INSTANCE.clearsEditor(mode)) {
                    editor.requestFocus();
                    editor.getText().clear();
                }
                else if (MODE_STRUCTURAL_FIELDS.equals(mode) && secondEditor != null) {
                    editor.clearFocus(); secondEditor.requestFocus();
                }
            });
            // Keep real lifecycle controls above the IME-obscured editor rows.
            root.addView(action, 1);
        }
        Button finish = new Button(this);
        finish.setText(FINISH_DESCRIPTION);
        finish.setContentDescription(FINISH_DESCRIPTION);
        finish.setOnClickListener(view -> finishAndRemoveTask());
        root.addView(finish);
        setContentView(root);
        editor.post(() -> {
            editor.requestFocus();
            InputMethodManager input = getSystemService(InputMethodManager.class);
            if (input != null) input.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private EditText editorFor(String mode) {
        EditText editor = new EditText(this);
        editor.setId(View.generateViewId());
        editor.setContentDescription(editorDescription(mode));
        editor.setMinHeight(dp(56));
        editor.setTextColor(Color.BLACK);
        editor.setBackgroundColor(Color.WHITE);
        editor.setHint("Generated test text");
        if (MODE_EMPTY.equals(mode)) editor.setText("");
        else if (MODE_MULTILINE.equals(mode)) {
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editor.setMinLines(4); editor.setText("Generated first line.\nGenerated second line.");
        } else if (MODE_PASSWORD.equals(mode)) {
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editor.setTransformationMethod(PasswordTransformationMethod.getInstance());
            editor.setText("generated-password-fixture");
        } else if (MODE_TRANSIENT.equals(mode)) editor.setText("Generated transient text.");
        else editor.setText("This are generated test words.");
        editor.setSelection(editor.getText().length());
        return editor;
    }

    public static String actionDescription(String mode) {
        if (MODE_STRUCTURAL_FIELDS.equals(mode)) return "Focus second generated field";
        return FixtureActionContract.INSTANCE.description(mode);
    }
    public static String editorDescription(String mode) {
        if (MODE_STRUCTURAL_FIELDS.equals(mode)) return STRUCTURAL_FIELD_ONE_DESCRIPTION;
        return EDITOR_DESCRIPTION_PREFIX + mode;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams params() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
}
