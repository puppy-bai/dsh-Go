import 'package:dsh_app/phone/automation_channel.dart';
import 'package:dsh_app/ui/phone/automation_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dsh/automation-test');
  final native = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  final parameters = <String, Object?>{
    'contact': {'type': 'string', 'default': '云星', 'required': true},
    'count': {'type': 'integer', 'default': 2, 'min': 1, 'max': 5, 'required': true},
  };
  final task = <String, Object?>{
    'id': 'test-task',
    'name': '测试任务',
    'version': 1,
    'enabled': true,
    'authorization': 'each',
    'apps': ['example.test'],
    'parameters': parameters,
  };

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    calls.clear();
  });
  tearDown(() => native.setMockMethodCallHandler(channel, null));

  test('parameter parsing enforces integer, finite number, enum and required value', () {
    expect(automationParameterValue('3', {'type': 'integer', 'min': 1, 'max': 5}), 3);
    for (final value in ['1.2', '0', '6']) {
      expect(() => automationParameterValue(value, {'type': 'integer', 'min': 1, 'max': 5}), throwsFormatException);
    }
    expect(() => automationParameterValue('NaN', {'type': 'number'}), throwsFormatException);
    expect(
      () => automationParameterValue('wrong', {
        'type': 'string',
        'enum': ['right'],
      }),
      throwsFormatException,
    );
    expect(() => automationParameterValue('', {'required': true}), throwsFormatException);
    expect(automationParameterValue('', {'type': 'string'}), isNull);
    expect(automationParameterValue('false', {'type': 'boolean'}), false);
  });

  test('missing native module returns an explicit failure', () async {
    final result = await AutomationChannel(methods: channel).list();
    expect(result['ok'], false);
    expect(result['reason'], isNotEmpty);
  });

  testWidgets('global mode needs an explicit confirmation', (tester) async {
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {'ok': true, 'tasks': [], 'proposals': [], 'globalAuto': false};
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    expect(find.textContaining('直到你手动关闭'), findsOneWidget);
    expect(calls.where((c) => c.method == 'automationSetGlobal'), isEmpty);
    await tester.tap(find.widgetWithText(TextButton, '取消'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'automationSetGlobal'), isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('task parameter form confirms before typed values reach native', (tester) async {
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'automationGet') return {'ok': true, 'task': task};
      return {
        'ok': true,
        'tasks': [task],
        'proposals': [],
        'globalAuto': false,
      };
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    await tester.tap(find.text('测试任务'));
    await tester.pumpAndSettle();
    expect(find.text('任务允许使用的应用: [example.test]'), findsOneWidget);
    expect(calls.where((c) => c.method == 'automationStart'), isEmpty);
    await tester.enterText(find.byType(TextFormField).last, '3');
    await tester.tap(find.widgetWithText(FilledButton, '确认运行'));
    await tester.pumpAndSettle();
    final started = calls.singleWhere((c) => c.method == 'automationStart');
    expect(started.arguments, {
      'id': 'test-task',
      'parameters': {'contact': '云星', 'count': 3},
    });
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('proposal review saves explicit scopes and does not execute', (tester) async {
    final proposal = {
      ...task,
      'proposalId': 'proposal-1',
      'definition': {...task, 'steps': []},
    };
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'ok': true,
        'tasks': [],
        'proposals': [proposal],
        'globalAuto': false,
      };
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    await tester.tap(find.text('测试任务'));
    await tester.pumpAndSettle();
    expect(find.text('查看完整流程与限制'), findsOneWidget);
    expect(calls.where((c) => c.method == 'automationSave'), isEmpty);
    await tester.tap(find.widgetWithText(FilledButton, '确认保存'));
    await tester.pumpAndSettle();
    final saved = calls.singleWhere((c) => c.method == 'automationSave');
    expect(saved.arguments, {
      'proposalId': 'proposal-1',
      'authorization': 'each',
      'parameterScopes': {
        'contact': {
          'enum': ['云星'],
        },
        'count': {
          'enum': [2],
        },
      },
    });
    expect(calls.where((c) => c.method == 'automationStart'), isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('a proposal can run once with explicit parameters without saving or granting authority', (tester) async {
    final proposal = {
      ...task,
      'proposalId': 'proposal-once',
      'definition': {...task, 'steps': []},
    };
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'ok': true,
        'tasks': [],
        'proposals': [proposal],
        'globalAuto': false,
      };
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    await tester.tap(find.text('测试任务'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, '仅运行本次'));
    await tester.pumpAndSettle();
    expect(find.textContaining('只授权这一次运行'), findsOneWidget);
    expect(find.text('查看完整流程与限制'), findsOneWidget);
    expect(calls.where((c) => c.method == 'automationStartOnce'), isEmpty);
    await tester.enterText(find.byType(TextFormField).last, '3');
    await tester.tap(find.widgetWithText(FilledButton, '确认运行'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'automationStartOnce').arguments, {
      'proposalId': 'proposal-once',
      'parameters': {'contact': '云星', 'count': 3},
    });
    expect(
      calls.where((c) => ['automationSave', 'automationStart', 'automationSetGlobal'].contains(c.method)),
      isEmpty,
    );
    expect(find.text('测试任务'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('local screenshot retention defaults off and requires explicit opt-in', (tester) async {
    var evidenceEnabled = false;
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'automationSetEvidence') evidenceEnabled = (call.arguments as Map)['enabled'] == true;
      return {'ok': true, 'tasks': [], 'proposals': [], 'globalAuto': false, 'evidenceEnabled': evidenceEnabled};
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    expect(tester.widget<Switch>(find.byType(Switch).last).value, false);
    await tester.tap(find.byType(Switch).last);
    await tester.pumpAndSettle();
    expect(find.textContaining('截图可能含私人内容'), findsOneWidget);
    expect(calls.where((c) => c.method == 'automationSetEvidence'), isEmpty);
    await tester.tap(find.widgetWithText(TextButton, '取消'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'automationSetEvidence'), isEmpty);
    await tester.tap(find.byType(Switch).last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '确认'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'automationSetEvidence').arguments, {'enabled': true});
    expect(tester.widget<Switch>(find.byType(Switch).last).value, true);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('local screenshot preview handles missing files and clearing needs confirmation', (tester) async {
    var evidence = <Object?>[
      {'runId': 'run-image', 'name': '截图测试任务', 'path': 'missing-evidence.png', 'createdAt': 1},
    ];
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'automationClearEvidence') evidence = [];
      return {'ok': true, 'tasks': [], 'proposals': [], 'globalAuto': false, 'evidence': evidence};
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    await tester.ensureVisible(find.text('截图测试任务'));
    await tester.tap(find.text('截图测试任务'));
    await tester.pumpAndSettle();
    expect(find.byType(InteractiveViewer), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, '关闭'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('清理截图证据'));
    await tester.tap(find.text('清理截图证据'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'automationClearEvidence'), isEmpty);
    await tester.tap(find.widgetWithText(FilledButton, '确认'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'automationClearEvidence'), hasLength(1));
    expect(find.text('截图测试任务'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('out-of-scope parameters require a second explicit one-time approval', (tester) async {
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'automationGet') return {'ok': true, 'task': task};
      if (call.method == 'automationStart') {
        return (call.arguments as Map)['approveScopes'] == true
            ? {'ok': true}
            : {'ok': false, 'reason': 'needs_confirmation'};
      }
      return {
        'ok': true,
        'tasks': [task],
        'proposals': [],
        'globalAuto': false,
      };
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    await tester.tap(find.text('测试任务'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, '其他联系人');
    await tester.tap(find.widgetWithText(FilledButton, '确认运行'));
    await tester.pumpAndSettle();
    expect(find.text('参数超出原授权范围'), findsOneWidget);
    expect(calls.where((c) => c.method == 'automationStart'), hasLength(1));
    await tester.tap(find.widgetWithText(FilledButton, '确认'));
    await tester.pumpAndSettle();
    final started = calls.where((c) => c.method == 'automationStart').last;
    expect((started.arguments as Map)['approveScopes'], true);
    expect((started.arguments as Map)['parameters'], {'contact': '其他联系人', 'count': 2});
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('paused task resumes with its existing run identifier', (tester) async {
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'ok': true,
        'tasks': [],
        'proposals': [],
        'globalAuto': false,
        'run': {'runId': 'run-1', 'name': '保留进度任务', 'state': 'paused', 'repairs': 2, 'checkpoint': 3},
      };
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    expect(find.textContaining('保留进度任务'), findsOneWidget);
    expect(find.textContaining('当前检查点: 3'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, '继续'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'automationResume').arguments, {'runId': 'run-1'});
    expect(calls.where((c) => c.method == 'automationStart'), isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('waiting for AI remains pausable and shows prior results', (tester) async {
    native.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'ok': true,
        'tasks': [],
        'proposals': [],
        'globalAuto': false,
        'run': {'runId': 'waiting-1', 'name': '等待决策任务', 'state': 'awaiting_ai', 'aiCalls': 1},
        'history': [
          {
            'runId': 'old-1',
            'name': '前一次已结束任务',
            'state': 'completed',
            'result': {'status': 'verified'},
          },
        ],
      };
    });
    await pumpPhone(tester, PhoneAutomationPage(channel: AutomationChannel(methods: channel)));
    expect(find.textContaining('等待决策任务'), findsOneWidget);
    expect(find.textContaining('前一次已结束任务'), findsOneWidget);
    expect(find.textContaining('AI 检查点: 1'), findsOneWidget);
    await tester.ensureVisible(find.widgetWithText(TextButton, '暂停'));
    await tester.tap(find.widgetWithText(TextButton, '暂停'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'automationPause').arguments, {'runId': 'waiting-1'});
    expect(calls.where((c) => c.method == 'automationResume'), isEmpty);
    await tester.pumpWidget(const SizedBox());
  });
}
