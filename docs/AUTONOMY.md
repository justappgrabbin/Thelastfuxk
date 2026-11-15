# The Last Fuxk — autonomous goal continuation

## What this version actually does

The user saves a specific goal in Cynthia's chat and switches **Autonomy** on. The Android foreground Watcher detects idle time and schedules a real `autonomous` job, executes the existing local `CodingAgent`, saves tool evidence and a text checkpoint, and schedules the next attempt after at least 10 minutes. After three cycles, or when work is blocked or otherwise unverified, it pauses until the user resets or changes the goal.

The goal, session identifier, completed-cycle count, next eligible time, and last evidence are stored in Android SharedPreferences. The jobs/results are persisted in the existing TaskQueue and ConversationLog. Changing the goal creates a new session; results from older sessions must not overwrite it. Stopping the Watcher interrupts in-progress work; switching Autonomy off prevents further unattended tool calls.

### Autonomous permissions

Autonomous goals may only call the existing local `list`, `read`, and `write` tools within the app's workspace. `write` may create or append; overwriting an existing file is denied. No autonomous Linux shell, Android shell, Tasker, or accessibility/phone Hands. These capabilities remain available only to user-directed tasks with existing settings and per-action review.

A saved goal does not implicitly authorize broader side effects. There is no arbitrary command execution, deletion, self-installation, network API access, or hidden permission escalation in unattended mode.

### Using it

1. Open Cynthia's chat and enter a clear **Autonomous goal**.
2. Select **Save goal**. Saving a changed goal resets the current cycle and leaves autonomy paused.
3. Turn the **Autonomy** switch on. The Watcher must be running. Check the **Activity** tab for real work evidence.
4. Turn Autonomy off to pause further work. Use **Restart cycles** to reset the limit for the saved goal, then turn the switch on again.

If no local Qwen model weights are installed, `LocalCoder` may need an initial download/import of approximately 1.12 GB; the APK does **not** contain the model weights. On-device execution still needs Android permissions and available memory. Background scheduling only runs while the foreground agent service is alive; it is not an Android guaranteed periodic scheduler and may be interrupted by device power management.

### Research relationship

Lu et al., *Proactive Agent: Shifting LLM Agents from Reactive Responses to Active Assistance*, arXiv:2410.12361 (2024): https://arxiv.org/abs/2410.12361

The research studies proactive assistance decisions using human accept/reject judgments, a reward model, and the ProactiveBench event set. This implementation **does not** ship that reward model, ProactiveBench, or its learned decision policy. It uses explicit user-approved goals, a deterministic scheduling policy, the existing local model, recorded evidence, and conservative access boundaries. A future acceptance/rejection dataset and prediction model would be additional functionality requiring separate implementation and evaluation.

### Tests

The `AutonomyRegression` host suite covers opt-in, goal/session validation, cooldown, cycle limit, paused outcomes, prompt continuity, workspace boundaries, overwrite rejection, and denial of unattended shell/Tasker/phone tools. `tests/run.sh` also runs existing agent, Tasker bridge, and computer regression checks. GitHub Actions builds an APK as a test artifact; successful compilation is not proof of autonomous phone operation. Test on the actual Android device before relying on it.
