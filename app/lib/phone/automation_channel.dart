import 'package:flutter/services.dart';

import '../l10n/l10n.dart';

/// Shares the native phone channel; task authorization remains on the native side.
class AutomationChannel {
  AutomationChannel({MethodChannel? methods}) : _methods = methods ?? const MethodChannel('dsh/phone');

  final MethodChannel _methods;

  Future<Map<String, Object?>> invoke(String method, [Map<String, Object?>? arguments]) async {
    try {
      final value = await _methods.invokeMapMethod<String, Object?>(method, arguments);
      return value ?? {'ok': false, 'reason': appL10n.automationUnavailable};
    } on MissingPluginException {
      return {'ok': false, 'reason': appL10n.automationUnavailable};
    } on PlatformException catch (e) {
      return {'ok': false, 'reason': e.message ?? e.code};
    }
  }

  Future<Map<String, Object?>> list() => invoke('automationList');
  Future<Map<String, Object?>> get(String id) => invoke('automationGet', {'id': id});
  Future<Map<String, Object?>> propose(Map<String, Object?> definition) =>
      invoke('automationPropose', {'definition': definition});
  Future<Map<String, Object?>> save(String proposalId, String authorization, Map<String, Object?> scopes) =>
      invoke('automationSave', {'proposalId': proposalId, 'authorization': authorization, 'parameterScopes': scopes});
  Future<Map<String, Object?>> start(String id, Map<String, Object?> parameters, {bool approveScopes = false}) =>
      invoke('automationStart', {'id': id, 'parameters': parameters, if (approveScopes) 'approveScopes': true});
  Future<Map<String, Object?>> startOnce(String proposalId, Map<String, Object?> parameters) =>
      invoke('automationStartOnce', {'proposalId': proposalId, 'parameters': parameters});
  Future<Map<String, Object?>> control(String action, String runId) => invoke('automation$action', {'runId': runId});
  Future<Map<String, Object?>> setGlobal(bool enabled) => invoke('automationSetGlobal', {'enabled': enabled});
  Future<Map<String, Object?>> setEvidence(bool enabled) => invoke('automationSetEvidence', {'enabled': enabled});
  Future<Map<String, Object?>> evidence() => invoke('automationEvidence');
  Future<Map<String, Object?>> clearEvidence() => invoke('automationClearEvidence');
  Future<Map<String, Object?>> setEnabled(String id, bool enabled) =>
      invoke('automationDisable', {'id': id, 'enabled': enabled});
  Future<Map<String, Object?>> delete(String id) => invoke('automationDelete', {'id': id});
}

Map<String, Object?> automationMap(Object? value) =>
    value is Map ? value.map((key, value) => MapEntry('$key', value)) : const {};

List<Map<String, Object?>> automationMaps(Object? value) =>
    value is List ? value.whereType<Map>().map(automationMap).toList() : const [];

/// Parse form values without silently truncating integers or accepting invalid numbers.
Object? automationParameterValue(String text, Map<String, Object?> spec) {
  if (text.isEmpty) {
    if (spec['required'] == true) throw FormatException(appL10n.automationRequired);
    return null;
  }
  final type = spec['type'] ?? 'string';
  final Object value;
  if (type == 'integer') {
    value = int.tryParse(text) ?? (throw FormatException(appL10n.automationInvalidValue));
  } else if (type == 'number') {
    final parsed = num.tryParse(text);
    if (parsed == null || !parsed.isFinite) throw FormatException(appL10n.automationInvalidValue);
    value = parsed;
  } else if (type == 'boolean') {
    if (text != 'true' && text != 'false') throw FormatException(appL10n.automationInvalidValue);
    value = text == 'true';
  } else {
    value = text;
  }
  if (value is num) {
    if (spec['min'] case final num min when value < min) throw FormatException(appL10n.automationInvalidValue);
    if (spec['max'] case final num max when value > max) throw FormatException(appL10n.automationInvalidValue);
  }
  if (value is String) {
    if (spec['maxLength'] case final int max when value.length > max) {
      throw FormatException(appL10n.automationInvalidValue);
    }
  }
  final choices = spec['enum'];
  if (choices is List && !choices.contains(value)) throw FormatException(appL10n.automationInvalidValue);
  return value;
}
