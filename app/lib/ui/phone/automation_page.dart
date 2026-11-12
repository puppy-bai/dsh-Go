import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../phone/automation_channel.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

class PhoneAutomationPage extends StatefulWidget {
  const PhoneAutomationPage({super.key, this.channel});

  final AutomationChannel? channel;

  @override
  State<PhoneAutomationPage> createState() => _PhoneAutomationPageState();
}

class _PhoneAutomationPageState extends State<PhoneAutomationPage> with WidgetsBindingObserver {
  late final AutomationChannel _channel = widget.channel ?? AutomationChannel();
  Timer? _timer;
  Map<String, Object?> _data = const {};
  String? _error;
  bool _refreshing = false;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_refresh());
    _poll();
  }

  void _poll() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => unawaited(_refresh()));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _poll();
      unawaited(_refresh());
    } else {
      _timer?.cancel();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_refreshing) return;
    _refreshing = true;
    final result = await _channel.list();
    _refreshing = false;
    if (!mounted) return;
    setState(() {
      if (result['ok'] == false) {
        _error = '${result['reason'] ?? context.l10n.automationUnavailable}';
      } else {
        _data = result;
        _error = null;
      }
    });
  }

  Future<void> _perform(Future<Map<String, Object?>> Function() action) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final result = await action();
      if (!mounted) return;
      if (result['ok'] == false) {
        _message('${result['reason'] ?? result['code'] ?? context.l10n.automationUnavailable}');
      }
      await _refresh();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _message(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<bool> _confirm(String title, String content) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: SingleChildScrollView(child: SelectableText(content)),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.l10n.commonCancel)),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(context.l10n.automationConfirm)),
          ],
        ),
      ) ??
      false;

  Future<void> _setGlobal(bool enabled) async {
    if (enabled && !await _confirm(context.l10n.automationGlobal, context.l10n.automationGlobalConfirm)) return;
    await _perform(() => _channel.setGlobal(enabled));
  }

  Future<void> _setEvidence(bool enabled) async {
    if (enabled && !await _confirm(context.l10n.automationEvidence, context.l10n.automationEvidenceConfirm)) return;
    await _perform(() => _channel.setEvidence(enabled));
  }

  Future<void> _clearEvidence() async {
    if (!await _confirm(context.l10n.automationClearEvidence, context.l10n.automationClearEvidenceHint)) return;
    await _perform(_channel.clearEvidence);
  }

  Future<void> _viewEvidence(Map<String, Object?> evidence) => showDialog<void>(
    context: context,
    builder: (context) => Dialog(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('${evidence['name'] ?? evidence['runId']}'),
            const SizedBox(height: 8),
            Flexible(
              child: InteractiveViewer(
                child: Image.file(
                  File('${evidence['path']}'),
                  errorBuilder: (_, _, _) => Text(context.l10n.automationEvidenceUnavailable),
                ),
              ),
            ),
            TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonClose)),
          ],
        ),
      ),
    ),
  );

  Future<void> _import() async {
    var text = '';
    String? error;
    final definition = await showDialog<Map<String, Object?>>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => AlertDialog(
          title: Text(context.l10n.automationImport),
          content: SizedBox(
            width: 560,
            child: TextField(
              onChanged: (value) => text = value,
              maxLines: 12,
              decoration: InputDecoration(hintText: context.l10n.automationImportHint, errorText: error),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
            FilledButton(
              onPressed: () {
                try {
                  final value = jsonDecode(text);
                  if (value is! Map) throw const FormatException();
                  Navigator.pop(context, automationMap(value));
                } on FormatException {
                  update(() => error = context.l10n.automationInvalidJson);
                }
              },
              child: Text(context.l10n.automationReview),
            ),
          ],
        ),
      ),
    );
    if (definition == null || !mounted) return;
    await _perform(() => _channel.propose(definition));
  }

  Future<void> _review(Map<String, Object?> proposal) async {
    final definition = automationMap(proposal['definition']);
    final parameters = automationMap(proposal['parameters'] ?? definition['parameters']);
    final scopeValues = <String, String>{};
    final unrestricted = <String, bool>{};
    for (final entry in parameters.entries) {
      final spec = automationMap(entry.value);
      final choices = spec['enum'] is List ? spec['enum'] as List : [if (spec.containsKey('default')) spec['default']];
      scopeValues[entry.key] = choices.map((v) => '$v').join('\n');
      unrestricted[entry.key] = false;
    }
    var always = false;
    String? error;
    final approval = await showDialog<Map<String, Object?>>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => AlertDialog(
          title: Text(context.l10n.automationReview),
          content: SizedBox(
            width: 560,
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('${proposal['name'] ?? definition['name'] ?? proposal['id']}'),
                  const SizedBox(height: 8),
                  Text(context.l10n.automationApps),
                  SelectableText('${(proposal['apps'] ?? definition['apps'] ?? [])}'),
                  const SizedBox(height: 8),
                  Text(context.l10n.automationReviewHint),
                  ExpansionTile(
                    title: Text(context.l10n.automationDefinition),
                    children: [SelectableText(const JsonEncoder.withIndent('  ').convert(definition))],
                  ),
                  for (final entry in parameters.entries) ...[
                    const SizedBox(height: 12),
                    Text(entry.key, style: Theme.of(context).textTheme.titleSmall),
                    Text('${entry.value}'),
                    TextFormField(
                      initialValue: scopeValues[entry.key],
                      onChanged: (value) => scopeValues[entry.key] = value,
                      enabled: unrestricted[entry.key] != true,
                      minLines: 1,
                      maxLines: 4,
                      decoration: InputDecoration(labelText: context.l10n.automationAllowedValues),
                    ),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(context.l10n.automationAnyValue),
                      value: unrestricted[entry.key],
                      onChanged: (value) => update(() => unrestricted[entry.key] = value == true),
                    ),
                  ],
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(context.l10n.automationAlways),
                    subtitle: Text(context.l10n.automationAlwaysHint),
                    value: always,
                    onChanged: (value) => update(() => always = value),
                  ),
                  if (error != null) Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
            OutlinedButton(
              onPressed: () => Navigator.pop(context, {'runOnce': true}),
              child: Text(context.l10n.automationRunOnce),
            ),
            FilledButton(
              onPressed: () {
                try {
                  final scopes = <String, Object?>{};
                  for (final entry in parameters.entries) {
                    if (unrestricted[entry.key] == true) {
                      scopes[entry.key] = {'any': true};
                    } else {
                      final spec = automationMap(entry.value);
                      final values = scopeValues[entry.key]!.split('\n').where((v) => v.isNotEmpty).map((v) {
                        return automationParameterValue(v, {...spec, 'required': true});
                      }).toList();
                      if (values.isEmpty) throw FormatException(context.l10n.automationScopeRequired);
                      scopes[entry.key] = {'enum': values};
                    }
                  }
                  Navigator.pop(context, {'authorization': always ? 'always' : 'each', 'scopes': scopes});
                } on FormatException catch (e) {
                  update(() => error = e.message);
                }
              },
              child: Text(context.l10n.automationSave),
            ),
          ],
        ),
      ),
    );
    if (approval == null || !mounted) return;
    if (approval['runOnce'] == true) {
      await _runOnce(proposal);
      return;
    }
    await _perform(
      () =>
          _channel.save('${proposal['proposalId']}', '${approval['authorization']}', automationMap(approval['scopes'])),
    );
  }

  Future<void> _runOnce(Map<String, Object?> proposal) async {
    final definition = automationMap(proposal['definition']);
    final values = await showDialog<Map<String, Object?>>(
      context: context,
      builder: (_) => _ParameterDialog(
        name: '${proposal['name'] ?? definition['name']}',
        apps: proposal['apps'] ?? definition['apps'],
        specs: automationMap(proposal['parameters'] ?? definition['parameters']),
        definition: definition,
      ),
    );
    if (values == null || !mounted) return;
    await _perform(() => _channel.startOnce('${proposal['proposalId']}', values));
  }

  Future<void> _run(Map<String, Object?> summary) async {
    final result = await _channel.get('${summary['id']}');
    if (!mounted) return;
    if (result['ok'] == false) {
      _message('${result['reason']}');
      return;
    }
    final task = automationMap(result['task']);
    final specs = automationMap(task['parameters'] ?? automationMap(task['definition'])['parameters']);
    final values = await showDialog<Map<String, Object?>>(
      context: context,
      builder: (_) => _ParameterDialog(
        name: '${summary['name']}',
        apps: task['apps'] ?? automationMap(task['definition'])['apps'],
        specs: specs,
      ),
    );
    if (values == null || !mounted) return;
    await _perform(() async {
      var started = await _channel.start('${summary['id']}', values);
      if (('${started['reason']}'.startsWith('needs_confirmation') ||
              '${started['code']}'.startsWith('needs_confirmation')) &&
          mounted) {
        final accepted = await _confirm(
          context.l10n.automationScopeConfirm,
          '${context.l10n.automationScopeConfirmHint}\n\n${const JsonEncoder.withIndent('  ').convert(values)}',
        );
        if (accepted) started = await _channel.start('${summary['id']}', values, approveScopes: true);
      }
      return started;
    });
  }

  String _stateLabel(String state) => switch (state) {
    'running' => context.l10n.automationRunning,
    'paused' => context.l10n.automationPaused,
    'awaiting_ai' || 'awaiting_repair' => context.l10n.automationAwaitingAi,
    'completed' => context.l10n.automationCompleted,
    'stopped' => context.l10n.automationStopped,
    'interrupted' => context.l10n.automationInterrupted,
    _ => state,
  };

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final tasks = automationMaps(_data['tasks']);
    final proposals = automationMaps(_data['proposals']);
    final evidence = automationMaps(_data['evidence']);
    final runs = [...automationMaps(_data['runs'] ?? _data['history'])];
    final current = automationMap(_data['run']);
    if (current.isNotEmpty && !runs.any((r) => r['runId'] == current['runId'])) runs.insert(0, current);
    return AppPage.list(
      title: l.automationTitle,
      subtitle: Text(l.automationSubtitle),
      actions: [IconButton(icon: const Icon(AppIcons.refresh), tooltip: l.automationRefresh, onPressed: _refresh)],
      children: [
        if (_error != null) PhoneNote(text: _error!, tone: NoteTone.warn),
        ListPageCard(
          children: [
            ListPageRow(
              icon: AppIcons.noConfirm,
              title: l.automationGlobal,
              subtitle: l.automationGlobalHint,
              subtitleMaxLines: 3,
              trailing: Switch(value: _data['globalAuto'] == true, onChanged: _busy ? null : _setGlobal),
            ),
            ListPageRow(icon: AppIcons.appRules, title: l.automationImport, onTap: _busy ? null : _import),
            ListPageRow(
              icon: AppIcons.appRules,
              title: l.automationEvidence,
              subtitle: l.automationEvidenceHint,
              subtitleMaxLines: 3,
              trailing: Switch(value: _data['evidenceEnabled'] == true, onChanged: _busy ? null : _setEvidence),
            ),
          ],
        ),
        if (evidence.isNotEmpty) ...[
          const SizedBox(height: 16),
          ListPageCard(
            title: l.automationEvidence,
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    for (final item in evidence)
                      SizedBox(
                        width: 112,
                        child: InkWell(
                          onTap: () => _viewEvidence(item),
                          child: Column(
                            children: [
                              Image.file(
                                File('${item['path']}'),
                                width: 112,
                                height: 112,
                                cacheWidth: 240,
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) =>
              const SizedBox(height: 112, child: Icon(AppIcons.imageBroken)),
                              ),
                              Text('${item['name'] ?? item['runId']}', maxLines: 2, overflow: TextOverflow.ellipsis),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              ListPageRow(title: l.automationClearEvidence, onTap: _busy ? null : _clearEvidence),
            ],
          ),
        ],
        if (proposals.isNotEmpty) ...[
          const SizedBox(height: 16),
          ListPageCard(
            title: l.automationProposals,
            children: [
              for (final proposal in proposals)
                ListPageRow(
                  title: '${proposal['name'] ?? proposal['id']}',
                  subtitle: '${proposal['apps'] ?? ''}',
                  onTap: _busy ? null : () => _review(proposal),
                ),
            ],
          ),
        ],
        const SizedBox(height: 16),
        ListPageCard(
          title: l.automationSaved,
          children: [
            if (tasks.isEmpty)
              ListPageRow(title: l.automationEmpty, subtitle: l.automationEmptyHint, subtitleMaxLines: 3),
            for (final task in tasks)
              ListPageRow(
                title: '${task['name'] ?? task['id']}',
                subtitle:
                    '${task['enabled'] == false
                        ? l.automationDisabled
                        : task['authorization'] == 'always'
                        ? l.automationAlways
                        : l.automationEach} · v${task['version'] ?? 1}',
                onTap: _busy || task['enabled'] == false ? null : () => _run(task),
                trailing: PopupMenuButton<String>(
                  enabled: !_busy,
                  onSelected: (action) async {
                    if (action == 'delete') {
                      if (await _confirm(l.automationDelete, l.automationDeleteHint)) {
                        await _perform(() => _channel.delete('${task['id']}'));
                      }
                    } else {
                      await _perform(() => _channel.setEnabled('${task['id']}', task['enabled'] == false));
                    }
                  },
                  itemBuilder: (_) => [
                    PopupMenuItem(
                      value: 'enabled',
                      child: Text(task['enabled'] == false ? l.automationEnable : l.automationDisable),
                    ),
                    PopupMenuItem(value: 'delete', child: Text(l.automationDelete)),
                  ],
                ),
              ),
          ],
        ),
        if (runs.isNotEmpty) ...[
          const SizedBox(height: 16),
          ListPageCard(title: l.automationRuns, children: [for (final run in runs) _runCard(run)]),
        ],
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _runCard(Map<String, Object?> run) {
    final l = context.l10n;
    final state = '${run['state'] ?? run['status'] ?? ''}';
    final active = run['isActive'] == true || ['running', 'paused', 'awaiting_ai', 'awaiting_repair'].contains(state);
    final id = '${run['runId']}';
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${run['name'] ?? run['taskId']} · ${_stateLabel(state)}',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          const SizedBox(height: 6),
          Text(
            '${l.automationProgress}: ${run['completed'] ?? 0} · ${l.automationRepairs}: ${run['repairs'] ?? 0}/2 · ${l.automationAiCalls}: ${run['aiCalls'] ?? 0}',
          ),
          Text('${l.automationElapsed}: ${((run['elapsedMs'] as num?) ?? 0) ~/ 1000}s'),
          if (run['checkpoint'] != null) SelectableText('${l.automationCheckpoint}: ${run['checkpoint']}'),
          if (run['reason'] != null) SelectableText('${run['reason']}'),
          if (run['prompt'] != null) ...[
            const SizedBox(height: 6),
            Text(l.automationAwaitingAiHint),
            SelectableText('${run['prompt']}'),
          ],
          if (run['result'] != null) SelectableText(const JsonEncoder.withIndent('  ').convert(run['result'])),
          if (active)
            Wrap(
              spacing: 8,
              children: [
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => _perform(() => _channel.control(state == 'paused' ? 'Resume' : 'Pause', id)),
                  child: Text(state == 'paused' ? l.automationResume : l.automationPause),
                ),
                TextButton(
                  onPressed: _busy ? null : () => _perform(() => _channel.control('Stop', id)),
                  child: Text(l.commonStop),
                ),
              ],
            ),
        ],
      ),
    );
  }
}

class _ParameterDialog extends StatefulWidget {
  const _ParameterDialog({required this.name, required this.apps, required this.specs, this.definition});

  final String name;
  final Object? apps;
  final Map<String, Object?> specs;
  final Map<String, Object?>? definition;

  @override
  State<_ParameterDialog> createState() => _ParameterDialogState();
}

class _ParameterDialogState extends State<_ParameterDialog> {
  final _form = GlobalKey<FormState>();
  late final _controllers = {
    for (final entry in widget.specs.entries)
      entry.key: TextEditingController(text: '${automationMap(entry.value)['default'] ?? ''}'),
  };

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.name),
    content: SizedBox(
      width: 480,
      child: SingleChildScrollView(
        child: Form(
          key: _form,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(widget.definition == null ? context.l10n.automationRunHint : context.l10n.automationRunOnceHint),
              const SizedBox(height: 8),
              SelectableText('${context.l10n.automationApps}: ${widget.apps ?? []}'),
              if (widget.definition != null)
                ExpansionTile(
                  title: Text(context.l10n.automationDefinition),
                  children: [SelectableText(const JsonEncoder.withIndent('  ').convert(widget.definition))],
                ),
              for (final entry in widget.specs.entries)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: TextFormField(
                    controller: _controllers[entry.key],
                    decoration: InputDecoration(labelText: entry.key, helperText: '${entry.value}'),
                    validator: (value) {
                      try {
                        automationParameterValue(value ?? '', automationMap(entry.value));
                        return null;
                      } on FormatException catch (e) {
                        return e.message;
                      }
                    },
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
      FilledButton(
        onPressed: () {
          if (_form.currentState?.validate() != true) return;
          final parameters = <String, Object?>{};
          for (final entry in widget.specs.entries) {
            final value = automationParameterValue(_controllers[entry.key]!.text, automationMap(entry.value));
            if (value != null) parameters[entry.key] = value;
          }
          Navigator.pop(context, parameters);
        },
        child: Text(context.l10n.automationRun),
      ),
    ],
  );
}
