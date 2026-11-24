package ai.notebookai.proof;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * NOT a product UI. Runs {@link ProofRunner} on a background thread when launched, shows the one-line verdict, logs the full JSON under the tag
 * {@code NBAI_PROOF} and writes it to {@code files/proof-result.json} (readable with {@code adb shell run-as ai.notebookai.proof cat files/proof-result.json}).
 */
public final class ProofActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final TextView view = new TextView(this);
        view.setText("running engine proof...");
        setContentView(view);
        new Thread(() -> {
            ProofResult result = ProofRunner.run(getApplicationContext());
            String json = result.toJson();
            Log.i("NBAI_PROOF", json);
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "proof-result.json"))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e("NBAI_PROOF", "could not write result file", e);
            }
            runOnUiThread(() -> view.setText(result.allPassed() ? "engine proof: ALL CHECKS PASSED" : "engine proof: FAILED (see logcat NBAI_PROOF)"));
        }, "nbai-proof").start();
    }
}
