package com.echolink.mobile;

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
    public static final String EDITOR_TAG = "external-fixture-editor";
    public static final String SECOND_EDITOR_TAG = "external-fixture-editor-second";
    public static final String STRUCTURAL_FIELD_ONE_DESCRIPTION = "Generated structural field one";
    public static final String STRUCTURAL_FIELD_TWO_DESCRIPTION = "Generated structural field two";
    public static final String FIXTURE_ROOT_DESCRIPTION = "Manuscript external field fixture";
    public static final String LIFECYCLE_ACTION_TAG = "external-fixture-lifecycle-action";
    public static final long TRANSIENT_CLEAR_DELAY_MS = 900L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String mode = getIntent().getStringExtra(EXTRA_MODE) == null
            ? ""
            : getIntent().getStringExtra(EXTRA_MODE);
        final EditText editor = new EditText(this);
        editor.setId(View.generateViewId());
        editor.setTag(EDITOR_TAG);
        editor.setMinHeight(dp(56));
        editor.setTextColor(Color.BLACK);
        editor.setBackgroundColor(Color.WHITE);
        editor.setHint("Generated test text");
        if (MODE_EMPTY.equals(mode)) {
            editor.setText("");
        } else if (MODE_MULTILINE.equals(mode)) {
            editor.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            );
            editor.setMinLines(4);
            editor.setText("Generated first line.\nGenerated second line.");
        } else if (MODE_PASSWORD.equals(mode)) {
            editor.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            );
            editor.setTransformationMethod(PasswordTransformationMethod.getInstance());
            editor.setText("generated-password-fixture");
        } else if (MODE_TRANSIENT.equals(mode)) {
            editor.setText("Generated transient text.");
        } else {
            editor.setText("This are generated test words.");
        }
        editor.setSelection(editor.getText().length());

        final EditText secondEditor;
        if (MODE_STRUCTURAL_FIELDS.equals(mode)) {
            secondEditor = new EditText(this);
            secondEditor.setTag(SECOND_EDITOR_TAG);
            secondEditor.setContentDescription(STRUCTURAL_FIELD_TWO_DESCRIPTION);
            secondEditor.setMinHeight(dp(56));
            secondEditor.setTextColor(Color.BLACK);
            secondEditor.setBackgroundColor(Color.WHITE);
            secondEditor.setText("Second generated field.");
            secondEditor.setSelection(secondEditor.getText().length());
            editor.setId(android.R.id.input);
            editor.setContentDescription(STRUCTURAL_FIELD_ONE_DESCRIPTION);
            secondEditor.setId(android.R.id.input);
        } else {
            secondEditor = null;
        }

        final LinearLayout root = new LinearLayout(this);
        root.setContentDescription(FIXTURE_ROOT_DESCRIPTION);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(30), dp(20), dp(20));
        final TextView heading = new TextView(this);
        heading.setText("Manuscript generated field fixture");
        heading.setTextSize(18f);
        heading.setTextColor(Color.BLACK);
        root.addView(heading);
        if (secondEditor != null) {
            final LinearLayout firstRow = new LinearLayout(this);
            firstRow.addView(editor, matchWidthWrapHeight());
            root.addView(firstRow);
            final LinearLayout secondRow = new LinearLayout(this);
            secondRow.addView(secondEditor, matchWidthWrapHeight());
            root.addView(secondRow);
        } else {
            root.addView(editor, matchWidthWrapHeight());
        }

        if (isLifecycleMode(mode) || secondEditor != null) {
            final Button action = new Button(this);
            action.setTag(LIFECYCLE_ACTION_TAG);
            action.setText(lifecycleActionDescription(mode));
            action.setContentDescription(action.getText());
            action.setOnClickListener(view -> {
                if (MODE_DETACHED.equals(mode)) {
                    final ViewGroup parent = (ViewGroup) editor.getParent();
                    if (parent != null) parent.removeView(editor);
                } else if (MODE_TARGET_LOSS.equals(mode)) {
                    editor.clearFocus();
                    view.requestFocus();
                } else if (MODE_PACKAGE_SWITCH.equals(mode)) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } else if (MODE_STRUCTURAL_FIELDS.equals(mode) && secondEditor != null) {
                    editor.clearFocus();
                    secondEditor.requestFocus();
                }
            });
            root.addView(action);
        }
        setContentView(root);

        editor.post(() -> {
            editor.requestFocus();
            final InputMethodManager input = getSystemService(InputMethodManager.class);
            if (input != null) {
                input.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        if (MODE_TRANSIENT.equals(mode)) {
            editor.postDelayed(() -> editor.getText().clear(), TRANSIENT_CLEAR_DELAY_MS);
        }
    }

    public static String lifecycleActionDescription(String mode) {
        if (MODE_DETACHED.equals(mode)) return "Detach generated field";
        if (MODE_TARGET_LOSS.equals(mode)) return "Move focus from generated field";
        if (MODE_STRUCTURAL_FIELDS.equals(mode)) return "Focus second generated field";
        return "Switch from generated field";
    }

    private static boolean isLifecycleMode(String mode) {
        return MODE_DETACHED.equals(mode)
            || MODE_TARGET_LOSS.equals(mode)
            || MODE_PACKAGE_SWITCH.equals(mode);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
