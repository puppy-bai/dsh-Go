import 'dart:async';

import 'package:dsh_app/state/automation_coordinator.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Client extends DshClient {
  _Client() : super(DshConnection(Uri.parse('http://127.0.0.1:9')));

  final prompts = <String>[];
  final cancelled = <String>[];
  Completer<SessionCreated>? pendingCreate;
  bool failPrompt = false;
  void Function()? beforePromptFailure;

  @override
  Future<SessionCreated> createSession({
    String? cwd,
    String? workspaceId,
    String? agentPreset,
    String? sessionId,
  }) async => pendingCreate == null ? const SessionCreated('automation-only', null) : pendingCreate!.future;

  @override
  Future<String> prompt(
    String sessionId,
    String text, {
    List<PromptImage> images = const [],
    List<String> fileReceiptIds = const [],
    String? requestId,
    PromptMode mode = PromptMode.queue,
    String? timeZone,
  }) async {
    prompts.add(requestId!);
    if (failPrompt) {
      beforePromptFailure?.call();
      throw StateError('uncertain network acknowledgement');
    }
    return requestId;
  }

  @override
  Future<void> cancel(String sessionId) async {
    cancelled.add(sessionId);
  }
}

class _Local extends LocalConnectionController {
  _Local(this.client);
  final DshClient client;
  @override
  Future<HostConnection?> build() async => HostConnection(client: client, isLocal: true);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dsh/phone');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late Map<String, Object?> run;
  late List<MethodCall> calls;

  setUp(() {
    calls = [];
    run = {'runId': 'run-1', 'state': 'running', 'isActive': true, 'dispatchEpoch': 1};
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'automationPause') {
        final args = call.arguments as Map;
        if (args['runId'] != run['runId'] || args['checkpoint'] != run['checkpoint'] ||
            args['dispatchEpoch'] != run['dispatchEpoch'] || run['state'] != 'awaiting_ai') {
          return {'ok': false, 'reason': 'stale-checkpoint'};
        }
        run = {...run, 'state': 'paused', 'reason': 'checkpoint-dispatch-failed'};
      }
      return {'ok': true, 'run': run};
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  Future<ProviderContainer> attach(_Client client) async {
    final container = ProviderContainer(overrides: [localConnectionProvider.overrideWith(() => _Local(client))]);
    await container.read(localConnectionProvider.future);
    container.listen(automationCoordinatorProvider, (_, _) {});
    return container;
  }

  testWidgets('local running steps do not wake the model; each checkpoint is sent once', (tester) async {
    final client = _Client();
    final container = await attach(client);
    await tester.pump(const Duration(seconds: 2));
    expect(client.prompts, isEmpty);
    run = {...run, 'state': 'awaiting_ai', 'checkpoint': 'cp-1', 'kind': 'decision'};
    await tester.pump(const Duration(seconds: 1));
    await tester.pump(const Duration(seconds: 2));
    expect(client.prompts, ['automation-run-1-cp-1']);
    run = {...run, 'state': 'paused', 'checkpoint': null};
    await tester.pump(const Duration(seconds: 1));
    expect(client.cancelled, ['automation-only']);
    run = {...run, 'state': 'awaiting_ai', 'checkpoint': 'cp-2'};
    await tester.pump(const Duration(seconds: 1));
    expect(client.prompts, ['automation-run-1-cp-1', 'automation-run-1-cp-2']);
    container.dispose();
    await tester.pump();
  });

  testWidgets('a paused checkpoint is not prompted after slow session creation', (tester) async {
    final client = _Client()..pendingCreate = Completer<SessionCreated>();
    run = {...run, 'state': 'awaiting_ai', 'checkpoint': 'cp-1'};
    final container = await attach(client);
    await tester.pump();
    run = {...run, 'state': 'paused', 'checkpoint': null};
    client.pendingCreate!.complete(const SessionCreated('automation-only', null));
    await tester.pump();
    expect(client.prompts, isEmpty);
    container.dispose();
    await tester.pump();
  });

  testWidgets('ambiguous model submission pauses and is not retried', (tester) async {
    final client = _Client()..failPrompt = true;
    run = {...run, 'state': 'awaiting_ai', 'checkpoint': 'cp-1'};
    final container = await attach(client);
    await tester.pump();
    await tester.pump(const Duration(seconds: 3));
    expect(client.prompts, ['automation-run-1-cp-1']);
    expect(calls.where((c) => c.method == 'automationPause'), hasLength(1));
    expect(run['state'], 'paused');
    expect(run['reason'], 'checkpoint-dispatch-failed');
    expect((calls.firstWhere((c) => c.method == 'automationPause').arguments as Map)['checkpoint'], 'cp-1');
    container.dispose();
    await tester.pump();
  });

  testWidgets('a late dispatch failure cannot pause a newer checkpoint', (tester) async {
    final client = _Client()..failPrompt = true;
    run = {...run, 'state': 'awaiting_ai', 'checkpoint': 'cp-1'};
    client.beforePromptFailure = () {
      run = {...run, 'checkpoint': 'cp-2', 'dispatchEpoch': 2};
    };
    final container = await attach(client);
    await tester.pump();
    expect(run['state'], 'awaiting_ai');
    expect(run['checkpoint'], 'cp-2');
    expect(calls.where((c) => c.method == 'automationPause'), hasLength(1));
    container.dispose();
    await tester.pump();
  });

  test('stale checkpoint guidance never asks to pause its successor', () {
    final prompt = automationCheckpointPrompt({'runId': 'r', 'checkpoint': 'old'});
    expect(prompt, contains('不调用 pause/stop'));
    expect(prompt, isNot(contains('无法确定下一步或当前令牌失效，调用 task pause')));
  });

  testWidgets('disposed coordinator cancels a late-created session without prompting', (tester) async {
    final client = _Client()..pendingCreate = Completer<SessionCreated>();
    run = {...run, 'state': 'awaiting_ai', 'checkpoint': 'cp-1'};
    final container = await attach(client);
    await tester.pump();
    container.dispose();
    client.pendingCreate!.complete(const SessionCreated('late-session', null));
    await tester.pump();
    expect(client.prompts, isEmpty);
    expect(client.cancelled, ['late-session']);
  });
}
