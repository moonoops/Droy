#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Droy USB setup: read-only scan, reviewable plan, approved apply, verification.

No model credentials enter this tool. All device mutations use an explicitly
approved, device-bound plan; completing setup remains the app's responsibility.
"""
import argparse
import base64
from dataclasses import asdict
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

from enable_permissions import (Adb, AdbError, PermissionSetup, SetupError, RELEASE_PACKAGE as PACKAGE,
                                choose_device, find_adb, permission_is_granted)

PROVIDER = 'content://' + PACKAGE + '.permissions'
ROOT = Path(__file__).resolve().parents[1]
SHIZUKU_PACKAGE = 'moe.shizuku.privileged.api'
SCHEMA = 1
ASSISTANT_CAPABILITIES = {'allowObserve', 'allowControl', 'allowApps'}
SYSTEM_PERMISSIONS = {'accessibility', 'calendar', 'contacts', 'microphone',
                      'notifications', 'notificationAccess', 'usage', 'diagnostics',
                      'brightness', 'overlay'}
CONFIG_FIELDS = {'source', 'mode', 'capabilities', 'shizukuChoice'}
IDENTITY_FIELDS = {'companionName': 40, 'userAddress': 40, 'personality': 1000}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha256(path):
    result = hashlib.sha256()
    with open(path, 'rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(part)
    return result.hexdigest()


def capability_names():
    with (ROOT / 'runtime/mobile/assets/droy-capabilities.json').open(encoding='utf-8') as stream:
        return {group['preference'] for group in json.load(stream)['groups']}


def optional_shell(adb, *args):
    try:
        return adb.shell(*args)
    except AdbError:
        return None


def provider(adb, user, method, payload=None):
    arguments = ['content', 'call', '--user', str(user), '--uri', PROVIDER, '--method', method]
    if payload is not None:
        arguments.extend(['--arg', base64.urlsafe_b64encode(canonical(payload)).decode('ascii').rstrip('=')])
    output = adb.shell(*arguments)
    marker = re.search(r'\bsetupJson=', output)
    if not marker:
        raise SetupError('当前 APK 未返回安装状态；请在手机打开 Droy，或安装支持交互安装的版本')
    try:
        value, _ = json.JSONDecoder().raw_decode(output[marker.end():])
    except json.JSONDecodeError as error:
        raise SetupError('手机安装状态格式无效，未把结果作为成功') from error
    if not isinstance(value, dict) or value.get('schemaVersion') != SCHEMA:
        raise SetupError('手机安装状态版本不匹配，请更新本项目安装工具')
    return value


def scan(adb, serial=None):
    serial = choose_device(adb, serial)
    user = adb.shell('am', 'get-current-user')
    api = adb.shell('getprop', 'ro.build.version.sdk')
    if not user.isdigit() or not api.isdigit():
        raise SetupError('无法确定 Android 版本和当前用户；未修改设备')
    abi = adb.shell('getprop', 'ro.product.cpu.abilist').split(',')
    installed_path = optional_shell(adb, 'pm', 'path', '--user', user, PACKAGE) or ''
    installed = bool(re.search(r'(?m)^package:.+\.apk$', installed_path))
    package_dump = optional_shell(adb, 'dumpsys', 'package', PACKAGE) if installed else ''
    package_dump = package_dump or ''
    version = re.search(r'\bversionCode=(\d+)', package_dump)
    version_name = re.search(r'\bversionName=([^\s]+)', package_dump)
    updated = re.search(r'(?m)^\s*lastUpdateTime=(.+)$', package_dump)
    setup, setup_error = None, None
    if installed:
        try:
            setup = provider(adb, user, 'setup-status')
        except (AdbError, SetupError):
            setup_error = '当前安装未提供可读取的安装状态，升级时保留原选择'
    webview = optional_shell(adb, 'dumpsys', 'webviewupdate') or ''
    current_webview = re.search(r'Current WebView package[^\n]*:\s*\(([^,]+),\s*([^\)]+)\)', webview)
    free = optional_shell(adb, 'df', '-k', '/data') or ''
    free_columns = free.splitlines()[-1].split() if free.splitlines() else []
    free_bytes = int(free_columns[3]) * 1024 if len(free_columns) >= 4 and free_columns[3].isdigit() else None
    shizuku_path = optional_shell(adb, 'pm', 'path', '--user', user, SHIZUKU_PACKAGE) or ''
    root_hint = optional_shell(adb, 'sh', '-c', 'command -v su >/dev/null 2>&1 && echo present || echo unknown')
    system = setup.get('environment', {}).get('shizuku', {}) if setup else {}
    permissions = {}
    if installed:
        for key, permission in {'calendar': 'android.permission.READ_CALENDAR',
                                'contacts': 'android.permission.READ_CONTACTS',
                                'microphone': 'android.permission.RECORD_AUDIO',
                                'notifications': 'android.permission.POST_NOTIFICATIONS'}.items():
            permissions[key] = permission_is_granted(package_dump, user, permission) if package_dump else 'unknown'
    access = {}
    if installed:
        for key in ('enabled_accessibility_services', 'enabled_notification_listeners'):
            raw = optional_shell(adb, 'settings', '--user', user, 'get', 'secure', key)
            access[key] = 'unknown' if raw is None else bool(re.search(r'(?:^|:)' + re.escape(PACKAGE) + r'/', raw))
    choices = {key: setup.get(key) for key in ('mode', 'capabilities', 'identity', 'shizukuChoice', 'toolsPaused')} if setup else None
    binding = {'targetSerial': serial, 'androidUser': user, 'androidApi': int(api), 'abis': abi,
               'installed': installed, 'installedVersionCode': int(version.group(1)) if version else None,
               'installedVersion': version_name.group(1) if version_name else None,
               'lastUpdateTime': updated.group(1).strip() if updated else None,
               'choicesFingerprint': digest(choices), 'permissionSnapshot': permissions, 'systemAccess': access}
    return {'schemaVersion': SCHEMA, **binding, 'deviceFingerprint': digest(binding),
            'model': optional_shell(adb, 'getprop', 'ro.product.model') or 'unknown',
            'manufacturer': optional_shell(adb, 'getprop', 'ro.product.manufacturer') or 'unknown',
            'freeBytes': free_bytes,
            'webView': {'package': current_webview.group(1).strip(), 'version': current_webview.group(2).strip()}
                if current_webview else {'status': 'unknown'},
            'root': {'binaryPresent': root_hint == 'present', 'authorized': 'unknown'},
            'shizuku': {'installed': bool(shizuku_path.startswith('package:')),
                        'running': system.get('running', 'unknown'),
                        'authorized': system.get('authorized', 'unknown'),
                        'available': system.get('available', 'unknown')},
            'systemPermissions': permissions, 'setup': setup, 'setupNotice': setup_error,
            'signing': {'status': 'unknown', 'detail': '覆盖安装由 Android 校验签名；签名不匹配时停止，不卸载'},
            'compatible': int(api) >= 30 and 'arm64-v8a' in abi,
            'questions': ['Droy 的名字、性格及对你的称呼（可保留默认）',
                          '保留默认形象，或选择形象及主应用名称/图标高级定制',
                          '是否安装或启动本次需要的 Shizuku 等外部组件',
                          '核对本次功能及权限清单，是否允许电脑代为配置']}


def _string_pool(data, offset):
    _, header_size, size = struct.unpack_from('<HHI', data, offset)
    count, _, flags, strings_start, _ = struct.unpack_from('<IIIII', data, offset + 8)
    if count > 100000 or offset + size > len(data):
        raise ValueError('invalid string pool')
    result = []
    def length(index, utf8):
        if utf8:
            value = data[index]
            return (((value & 0x7f) << 8) | data[index + 1], index + 2) if value & 0x80 else (value, index + 1)
        value = struct.unpack_from('<H', data, index)[0]
        return (((value & 0x7fff) << 16) | struct.unpack_from('<H', data, index + 2)[0], index + 4) if value & 0x8000 else (value, index + 2)
    for index in range(count):
        start = offset + strings_start + struct.unpack_from('<I', data, offset + header_size + index * 4)[0]
        if flags & 0x100:
            _, start = length(start, True)
            size_bytes, start = length(start, True)
            result.append(data[start:start + size_bytes].decode('utf-8'))
        else:
            size_chars, start = length(start, False)
            result.append(data[start:start + size_chars * 2].decode('utf-16-le'))
    return result


def manifest_attributes(data):
    if data.lstrip().startswith(b'<'):
        root = ET.fromstring(data)
        return [(element.tag, {key.split('}')[-1]: value for key, value in element.attrib.items()}) for element in root.iter()]
    if len(data) < 8 or struct.unpack_from('<H', data)[0] != 3:
        raise ValueError('not an Android binary manifest')
    offset, strings, elements = struct.unpack_from('<H', data, 2)[0], [], []
    while offset + 8 <= len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from('<HHI', data, offset)
        if chunk_size < header_size or chunk_size < 8 or offset + chunk_size > len(data):
            raise ValueError('invalid Android manifest chunk')
        if chunk_type == 1:
            strings = _string_pool(data, offset)
        elif chunk_type == 0x102:
            name = struct.unpack_from('<I', data, offset + 20)[0]
            start, attribute_size, count = struct.unpack_from('<HHH', data, offset + 24)
            if attribute_size < 20 or count > 10000:
                raise ValueError('invalid Android attributes')
            attributes = {}
            for index in range(count):
                position = offset + 16 + start + attribute_size * index
                if position + 20 > offset + chunk_size:
                    raise ValueError('truncated Android attributes')
                _, key, raw = struct.unpack_from('<III', data, position)
                kind = data[position + 15]
                value = struct.unpack_from('<I', data, position + 16)[0]
                attributes[strings[key]] = strings[raw] if raw != 0xffffffff else strings[value] if kind == 3 else value
            elements.append((strings[name], attributes))
        offset += chunk_size
    return elements


def apk_info(path, expected_package=PACKAGE):
    path = Path(path).expanduser().resolve()
    try:
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo('AndroidManifest.xml')
            if info.file_size > 4 * 1024 * 1024:
                raise ValueError('manifest too large')
            attributes = manifest_attributes(archive.read(info))
            manifest = next(values for name, values in attributes if name == 'manifest')
            minimum = next((values.get('minSdkVersion', 1) for name, values in attributes if name == 'uses-sdk'), 1)
            package = manifest.get('package')
            if package != expected_package:
                raise SetupError('APK 包名与所选组件不一致，未安装')
            version = int(manifest.get('versionCode', 0))
            if version < 1:
                raise ValueError('missing versionCode')
    except (OSError, zipfile.BadZipFile, KeyError, ValueError, StopIteration, struct.error, IndexError, UnicodeError, ET.ParseError) as error:
        raise SetupError('APK 缺少有效的 Android 安装信息，请使用完整安装包') from error
    return {'path': str(path), 'sha256': file_sha256(path), 'bytes': path.stat().st_size,
            'packageName': package, 'versionCode': version, 'versionName': str(manifest.get('versionName', '')),
            'minSdk': int(minimum)}


def validate_identity(value):
    if not isinstance(value, dict) or set(value) - set(IDENTITY_FIELDS):
        raise SetupError('个性化配置包含未知字段；API Key 请在手机填写')
    clean = {}
    for key, text in value.items():
        if not isinstance(text, str):
            raise SetupError('个性化配置需要文字')
        # Match Android String.trim and Character.isISOControl, including C1.
        invalid_control = any((ord(c) < 32 or 127 <= ord(c) <= 159)
                              and not (key == 'personality' and c in '\n\t') for c in text)
        text = text.strip(''.join(chr(code) for code in range(33)))
        if len(text) > IDENTITY_FIELDS[key] or invalid_control:
            raise SetupError('个性化配置格式或长度无效；名称和称呼最多 40 个单行字符，性格最多 1000 个字符')
        if key == 'companionName' and not text:
            raise SetupError('Droy 名称不能为空')
        clean[key] = text
    return clean


def permissions_for(capabilities):
    result = set()
    if any(capabilities.get(key) for key in ('allowObserve', 'allowControl', 'allowScreenshot')):
        result.add('accessibility')
    for key, permission in {'allowCalendar': 'calendar', 'allowContacts': 'contacts',
                            'allowAudioMicrophone': 'microphone', 'allowNotifications': 'notificationAccess',
                            'allowUsage': 'usage', 'allowDiagnostics': 'usage',
                            'allowPrivacyUsage': 'usage', 'allowSettingsWrite': 'brightness'}.items():
        if capabilities.get(key):
            result.add(permission)
    if capabilities.get('allowDiagnostics') or capabilities.get('allowPrivacyUsage'):
        result.add('diagnostics')
    return sorted(result)


def parse_capabilities(values):
    capabilities = {}
    allowed = capability_names()
    for value in values:
        key, separator, raw = value.partition('=')
        if not separator or key not in allowed or raw not in ('true', 'false'):
            raise SetupError('--capability 格式为已知能力名=true 或 false')
        if key in capabilities:
            raise SetupError('同一能力不能在方案中重复设置')
        capabilities[key] = raw == 'true'
    return capabilities


def create_plan(snapshot, apk, *, mode=None, capabilities=None, identity=None,
                automatic_permissions=False, extra_permissions=None, shizuku_choice=None,
                shizuku_apk=None, branding=None):
    if not snapshot['compatible']:
        raise SetupError('此版本需要 Android 11 及以上的 ARM64 手机')
    if apk['minSdk'] > snapshot['androidApi']:
        raise SetupError('该安装包要求更高的 Android 版本')
    if snapshot['installedVersionCode'] and apk['versionCode'] < snapshot['installedVersionCode']:
        raise SetupError('所选 APK 比已安装版本旧；安装流程不自动降级')
    identity = validate_identity(identity or {})
    names = capability_names()
    requested = capabilities or {}
    if set(requested) - names or any(type(value) is not bool for value in requested.values()):
        raise SetupError('能力选择无效')
    if mode == 'dialogue' and any(requested.values()):
        raise SetupError('仅对话模式不能同时开启手机能力，请选择 custom 或 assistant')
    configuration = {'source': 'usb'}
    selected = {}
    if mode is not None:
        if mode not in ('dialogue', 'assistant', 'custom'):
            raise SetupError('安装模式无效')
        selected = {name: mode == 'assistant' and name in ASSISTANT_CAPABILITIES for name in sorted(names)}
        selected.update(requested)
        configuration.update(mode=mode, capabilities=selected)
    elif requested:
        selected = requested
        if not snapshot['installed']:
            selected = {name: False for name in sorted(names)}
            selected.update(requested)
            configuration['mode'] = 'custom'
        configuration['capabilities'] = selected
    elif not snapshot['installed']:
        mode = 'assistant'
        selected = {name: name in ASSISTANT_CAPABILITIES for name in sorted(names)}
        configuration.update(mode=mode, capabilities=selected)
    if selected.get('allowScreenshot') and not selected.get('allowObserve', (snapshot.get('setup') or {}).get('capabilities', {}).get('allowObserve', False)):
        raise SetupError('截图需要同时开启页面读取能力')
    if shizuku_choice is not None and shizuku_choice not in ('skip', 'use', 'install'):
        raise SetupError('Shizuku 选择无效')
    previous_choice = (snapshot.get('setup') or {}).get('shizukuChoice', 'skip')
    resolved_choice = shizuku_choice if shizuku_choice is not None else previous_choice if snapshot['installed'] else 'skip'
    if resolved_choice not in ('skip', 'use', 'install'):
        raise SetupError('手机的 Shizuku 选择无效，请重新检查')
    component_action = shizuku_choice if shizuku_choice is not None else 'preserve' if snapshot['installed'] else 'skip'
    if component_action != 'preserve':
        configuration['shizukuChoice'] = resolved_choice
    if component_action == 'install' and shizuku_apk is None:
        raise SetupError('安装 Shizuku 需要已核对官方来源的本地 APK')
    if shizuku_apk is not None and component_action != 'install':
        raise SetupError('Shizuku APK 必须在选择安装该组件时提供')
    if component_action == 'use' and not snapshot['shizuku']['installed']:
        raise SetupError('手机未安装 Shizuku；请先选择安装组件，或跳过增强通道')
    if shizuku_apk is not None and shizuku_apk['minSdk'] > snapshot['androidApi']:
        raise SetupError('所选 Shizuku 版本不支持此 Android 版本')
    extras = list(extra_permissions or [])
    if any(name not in {'notifications', 'overlay'} for name in extras):
        raise SetupError('附加系统权限无效')
    permissions = sorted(set(permissions_for(selected) + extras)) if automatic_permissions else []
    if extras and not automatic_permissions:
        raise SetupError('附加系统权限需选择允许电脑代为配置')
    plan = {'schemaVersion': SCHEMA, 'kind': 'droy-usb-install',
            'targetSerial': snapshot['targetSerial'], 'androidUser': snapshot['androidUser'],
            'deviceFingerprint': snapshot['deviceFingerprint'],
            'previousVersionCode': snapshot['installedVersionCode'], 'apk': apk,
            'configuration': configuration, 'identity': identity,
            'automaticPermissions': automatic_permissions, 'extraPermissions': extras,
            'permissions': permissions, 'shizuku': {'choice': resolved_choice, 'action': component_action, 'apk': shizuku_apk},
            'branding': branding or {'variant': 'standard'},
            'modelSetup': 'phone',
            'phoneActions': ['在 Droy 的模型配置页输入 API Key 并测试；API Key 不进入本安装方案',
                             '完成 Android 要求本人确认的授权，并在 Droy 中运行最终检查'],
            'permissionNotice': '无障碍代配包括 WRITE_SECURE_SETTINGS 与 DUMP，保留其他应用服务，并验证实际连接' if 'accessibility' in permissions else '',
            'upgradePolicy': '保留数据与未明确修改的配置；签名冲突停止，不卸载、不清空数据'}
    if snapshot['installed'] and (snapshot.get('setup') or {}).get('toolsPaused') is True and any(selected.values()) and 'mode' not in configuration:
        plan['phoneActions'].append('手机能力当前已暂停；本次只更新选定开关，需你在手机主动恢复后才能使用，不会自动解除暂停')
    plan['approvalDigest'] = digest(plan)
    validate_plan(plan)
    return plan


def validate_plan(plan):
    allowed = {'schemaVersion', 'kind', 'targetSerial', 'androidUser', 'deviceFingerprint',
               'previousVersionCode', 'apk', 'configuration', 'identity', 'automaticPermissions',
               'extraPermissions', 'permissions', 'shizuku', 'branding', 'modelSetup', 'phoneActions',
               'permissionNotice', 'upgradePolicy', 'approvalDigest'}
    if not isinstance(plan, dict) or set(plan) != allowed or plan['schemaVersion'] != SCHEMA or plan['kind'] != 'droy-usb-install':
        raise SetupError('安装方案版本或字段无效，请重新生成方案')
    if not isinstance(plan['targetSerial'], str) or not re.fullmatch(r'[A-Za-z0-9_.:\-\[\]%]+', plan['targetSerial']):
        raise SetupError('安装方案目标设备无效')
    if not isinstance(plan['androidUser'], str) or not plan['androidUser'].isdigit():
        raise SetupError('安装方案 Android 用户无效')
    if not re.fullmatch(r'[a-f0-9]{64}', str(plan['deviceFingerprint'])):
        raise SetupError('安装方案设备状态无效')
    unsigned = dict(plan)
    approval = unsigned.pop('approvalDigest')
    if approval != digest(unsigned):
        raise SetupError('安装方案已改变，请重新审阅并批准新摘要')
    if validate_identity(plan['identity']) != plan['identity']:
        raise SetupError('个性化配置未规范化，请重新生成方案')
    config = plan['configuration']
    if not isinstance(config, dict) or set(config) - CONFIG_FIELDS or config.get('source') != 'usb':
        raise SetupError('安装方案配置字段无效')
    if 'mode' in config and config['mode'] not in ('dialogue', 'assistant', 'custom'):
        raise SetupError('安装模式无效')
    caps = config.get('capabilities', {})
    if not isinstance(caps, dict) or set(caps) - capability_names() or any(type(value) is not bool for value in caps.values()):
        raise SetupError('安装能力配置无效')
    if type(plan['automaticPermissions']) is not bool:
        raise SetupError('权限代配选择无效')
    extras = plan['extraPermissions']
    if not isinstance(extras, list) or any(name not in {'notifications', 'overlay'} for name in extras):
        raise SetupError('附加系统权限无效')
    expected = sorted(set(permissions_for(caps) + extras)) if plan['automaticPermissions'] else []
    if config.get('mode') == 'dialogue' and any(caps.values()):
        raise SetupError('对话模式不能同时开启手机能力，请选择自定义模式')
    if plan['permissions'] != expected:
        raise SetupError('系统权限不匹配已选择能力，未执行授权')
    shizuku = plan['shizuku']
    if (not isinstance(shizuku, dict) or set(shizuku) != {'choice', 'action', 'apk'}
            or shizuku['choice'] not in ('skip', 'use', 'install')
            or shizuku['action'] not in ('preserve', 'skip', 'use', 'install')):
        raise SetupError('Shizuku 安装选择无效')
    if shizuku['action'] == 'preserve':
        if 'shizukuChoice' in config:
            raise SetupError('保留 Shizuku 选择时不能同时改写其配置')
    elif config.get('shizukuChoice') != shizuku['choice'] or shizuku['action'] != shizuku['choice']:
        raise SetupError('Shizuku 配置与安装计划不一致')
    if (shizuku['action'] == 'install') != (shizuku['apk'] is not None):
        raise SetupError('Shizuku 安装包与选择不一致')
    for apk, expected_package in ((plan['apk'], PACKAGE), (shizuku['apk'], SHIZUKU_PACKAGE)):
        if apk is None:
            continue
        fields = {'path', 'sha256', 'bytes', 'packageName', 'versionCode', 'versionName', 'minSdk'}
        if not isinstance(apk, dict) or set(apk) != fields or apk['packageName'] != expected_package or not isinstance(apk['path'], str):
            raise SetupError('安装包信息无效')
        if (not re.fullmatch(r'[a-f0-9]{64}', str(apk['sha256'])) or type(apk['versionCode']) is not int
                or apk['versionCode'] < 1 or type(apk['bytes']) is not int or apk['bytes'] < 1
                or type(apk['minSdk']) is not int or apk['minSdk'] < 1
                or not isinstance(apk['versionName'], str) or '\x00' in apk['path']):
            raise SetupError('安装包版本或摘要无效')
    branding = plan['branding']
    if not isinstance(branding, dict) or set(branding) - {'variant', 'appName', 'appIconSha256', 'signingContinuityConfirmed'} or branding.get('variant') not in ('standard', 'personal'):
        raise SetupError('品牌定制信息无效')
    if branding['variant'] == 'personal' and branding.get('signingContinuityConfirmed') is not True:
        raise SetupError('个人定制包需先确认以后使用同一签名升级')
    if branding['variant'] == 'standard' and set(branding) != {'variant'}:
        raise SetupError('主应用名称和图标需选择个人构建模式')
    if 'appName' in branding:
        name = branding['appName']
        if not isinstance(name, str) or not name.strip() or len(name) > 40 or any(ord(c) < 32 for c in name) or '<' in name or '>' in name:
            raise SetupError('主应用名称格式无效')
    if 'appIconSha256' in branding and not re.fullmatch(r'[a-f0-9]{64}', str(branding['appIconSha256'])):
        raise SetupError('主应用图标摘要无效')
    if plan['modelSetup'] != 'phone':
        raise SetupError('模型凭据请在手机配置')
    return plan


def verify(adb, serial=None, complete=False):
    snapshot = scan(adb, serial)
    if not snapshot['installed']:
        raise SetupError('当前 Android 用户尚未安装 Droy')
    state = provider(adb, snapshot['androidUser'], 'setup-verify')
    if complete and state.get('canComplete') is True:
        state = provider(adb, snapshot['androidUser'], 'setup-complete')
    return {'schemaVersion': SCHEMA, 'ok': True, 'targetSerial': snapshot['targetSerial'],
            'androidUser': snapshot['androidUser'],
            'status': 'complete' if state.get('completed') and state.get('canComplete') is True else 'pending_phone', 'setup': state}


def shizuku_start_command(adb, user):
    """Select one documented starter from the installed, fixed package.

    Shizuku v13.6.0 runs its packaged libshizuku.so directly (Starter.kt).
    The v13.5.x manager instead writes start.sh into its external data directory.
    A missing or unexpected installation is handed back to the phone, not retried
    through other paths or privilege channels.
    """
    if not isinstance(user, str) or not user.isdigit():
        raise SetupError('无法确定 Shizuku 所属 Android 用户')
    paths = adb.shell('pm', 'path', '--user', user, SHIZUKU_PACKAGE)
    bases = [line[len('package:'):] for line in paths.splitlines()
             if line.startswith('package:') and line.endswith('/base.apk')]
    if len(bases) != 1:
        raise SetupError('未找到当前用户的 Shizuku 安装包，请在手机检查该组件')
    installation = bases[0][:-len('/base.apk')]
    expected = r'/data/app/(?:~~[A-Za-z0-9_+=-]+/)?' + re.escape(SHIZUKU_PACKAGE) + r'-[A-Za-z0-9_+=-]+'
    if not re.fullmatch(expected, installation):
        raise SetupError('Shizuku 安装目录不符合已验证格式，请在手机按官方引导启动')
    dump = adb.shell('dumpsys', 'package', SHIZUKU_PACKAGE)
    package = re.search(r'(?m)^([ \t]*)Package \[' + re.escape(SHIZUKU_PACKAGE) + r'\][^\n]*\n', dump)
    if package is None:
        raise SetupError('无法读取 Shizuku 自身的版本信息，请在手机检查该组件')
    lines = []
    for line in dump[package.end():].splitlines():
        if line.strip() and len(line) - len(line.lstrip()) <= len(package.group(1)):
            break
        lines.append(line)
    block = '\n'.join(lines)
    def field(name):
        values = re.findall(r'(?m)^\s*' + re.escape(name) + r'=(\S+)', block)
        if len(set(values)) > 1:
            raise SetupError('Shizuku 安装信息存在冲突，未执行启动命令')
        return values[0] if values else None
    if field('codePath') != installation:
        raise SetupError('Shizuku 安装目录与包管理器不一致，未执行启动命令')
    version = field('versionName') or ''
    if re.fullmatch(r'13\.6\.0(?:\.r[0-9]+\.[A-Za-z0-9]+)?', version):
        instruction_set = {'arm64-v8a': 'arm64', 'armeabi-v7a': 'arm',
                           'armeabi': 'arm', 'x86_64': 'x86_64', 'x86': 'x86'}.get(field('primaryCpuAbi'))
        if instruction_set is None:
            raise SetupError('Shizuku 的已安装 CPU 架构无法确认，请在手机按官方引导启动')
        native_directory = installation + '/lib/' + instruction_set
        if field('nativeLibraryDir') not in (None, native_directory) or field('legacyNativeLibraryDir') not in (None, installation + '/lib'):
            raise SetupError('Shizuku 原生目录与安装包不一致，未执行启动命令')
        starter = native_directory + '/libshizuku.so'
        adb.shell('test', '-x', starter)
        return (starter,)
    if re.fullmatch(r'13\.5(?:\.[0-9]+)?(?:\.r[0-9]+\.[A-Za-z0-9]+)?', version):
        script = '/storage/emulated/' + user + '/Android/data/' + SHIZUKU_PACKAGE + '/start.sh'
        adb.shell('test', '-r', script)
        return ('sh', script)
    raise SetupError('此 Shizuku 版本尚未验证启动方式，请在手机按官方引导启动')


def apply_plan(adb, plan, approval, serial=None):
    validate_plan(plan)
    if approval != plan['approvalDigest']:
        raise SetupError('执行前必须批准这份方案的 approvalDigest；未修改手机')
    if serial and serial != plan['targetSerial']:
        raise SetupError('命令设备与已批准设备不一致，未修改手机')
    snapshot = scan(adb, plan['targetSerial'])
    current_apk = apk_info(plan['apk']['path'])
    if current_apk != plan['apk']:
        raise SetupError('Droy 安装包已改变，请重新扫描并生成方案')
    shizuku = plan['shizuku']
    if shizuku['apk'] and apk_info(shizuku['apk']['path'], SHIZUKU_PACKAGE) != shizuku['apk']:
        raise SetupError('Shizuku 安装包已改变，请重新生成方案')
    same_device = snapshot['targetSerial'] == plan['targetSerial'] and snapshot['androidUser'] == plan['androidUser']
    if not same_device or snapshot['deviceFingerprint'] != plan['deviceFingerprint']:
        raise SetupError('手机或已安装版本已改变，请重新扫描并批准新方案')
    if not snapshot['compatible'] or current_apk['minSdk'] > snapshot['androidApi']:
        raise SetupError('当前手机不符合安装包要求')
    if snapshot['freeBytes'] is not None and snapshot['freeBytes'] < current_apk['bytes'] * 2:
        raise SetupError('手机存储空间不足，请释放空间后重新扫描')
    try:
        output = adb.run('install', '-r', '--user', plan['androidUser'], current_apk['path'], timeout_seconds=180)
    except AdbError as error:
        raise SetupError('安装被 Android 拒绝；请检查签名、版本及系统提示。没有卸载或清空数据') from error
    if not re.search(r'(?m)^Success\s*$', output):
        raise SetupError('Android 未确认安装成功；没有卸载或清空数据')
    setup = PermissionSetup(adb, plan['androidUser'], snapshot['androidApi'], package_name=PACKAGE)
    setup.open_app()
    configuration = {**plan['configuration'], **plan['identity']}
    provider(adb, plan['androidUser'], 'setup-configure', configuration)
    permission_results = setup.apply_selected(plan['permissions'])
    component_results = []
    if shizuku['action'] == 'install':
        try:
            result = adb.run('install', '-r', '--user', plan['androidUser'], shizuku['apk']['path'], timeout_seconds=180)
            if not re.search(r'(?m)^Success\s*$', result):
                raise SetupError('Android 未确认 Shizuku 安装成功')
            component_results.append({'name': 'Shizuku 安装', 'status': 'verified'})
        except AdbError as error:
            raise SetupError('Droy 已安装；Shizuku 安装被拒绝，没有卸载其他应用') from error
    if shizuku['action'] in ('install', 'use'):
        try:
            command = shizuku_start_command(adb, plan['androidUser'])
            adb.shell(*command, timeout_seconds=30)
            component_results.append({'name': 'Shizuku 启动请求', 'status': 'pending_verification',
                                      'detail': '仍需检查服务运行，并在手机允许 Droy 使用 Shizuku'})
        except (AdbError, SetupError):
            component_results.append({'name': 'Shizuku 启动', 'status': 'needs_action',
                                      'detail': '未确认此版本的启动条件或启动请求失败；请在手机打开 Shizuku，按官方引导启动后检查'})
    state = provider(adb, plan['androidUser'], 'setup-verify')
    failures = any(item.status == 'failed' for item in permission_results) or any(item['status'] == 'needs_action' for item in component_results)
    if not failures and state.get('canComplete') is True:
        state = provider(adb, plan['androidUser'], 'setup-complete')
    return {'schemaVersion': SCHEMA, 'ok': not failures, 'targetSerial': plan['targetSerial'],
            'androidUser': plan['androidUser'], 'installed': True,
            'status': 'needs_action' if failures else 'complete' if state.get('completed') and state.get('canComplete') is True else 'pending_phone',
            'permissions': [asdict(item) for item in permission_results], 'components': component_results,
            'setup': state, 'next': '在手机继续未完成的模型配置、系统确认和自检；完成后可在设置修改权限'}


def read_json(path):
    try:
        if Path(path).stat().st_size > 262144:
            raise SetupError('安装方案文件过大')
        with open(path, encoding='utf-8') as stream:
            return json.load(stream)
    except (OSError, json.JSONDecodeError) as error:
        raise SetupError('无法读取安装方案 JSON') from error


def main(argv=None):
    parser = argparse.ArgumentParser(description='Droy USB 交互安装：scan → plan → 批准 → apply → 手机自检')
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('scan', 'plan', 'apply', 'verify'):
        command = commands.add_parser(name)
        command.add_argument('--adb', default=argparse.SUPPRESS)
        command.add_argument('--serial', default=argparse.SUPPRESS)
        if name == 'plan':
            command.add_argument('--apk', required=True)
            command.add_argument('--output', required=True)
            command.add_argument('--mode', choices=('dialogue', 'assistant', 'custom'))
            command.add_argument('--capability', action='append', default=[])
            command.add_argument('--name')
            command.add_argument('--address')
            command.add_argument('--personality')
            command.add_argument('--permissions', choices=('manual', 'automatic'), default='manual')
            command.add_argument('--system-permission', action='append', choices=('notifications', 'overlay'), default=[])
            command.add_argument('--shizuku', choices=('skip', 'use', 'install'), help='明确跳过、使用或安装；升级未指定时保留原选择')
            command.add_argument('--shizuku-apk')
            command.add_argument('--shizuku-sha256', help='从可信发布信息核对的 Shizuku APK SHA-256')
            command.add_argument('--personal-build', action='store_true')
            command.add_argument('--app-name', help='已构建个人安装包的主应用名称，记入审阅方案')
            command.add_argument('--app-icon', help='已构建个人安装包采用的 PNG 图标，摘要记入方案')
            command.add_argument('--signing-continuity-confirmed', action='store_true')
        elif name == 'apply':
            command.add_argument('--plan', required=True)
            command.add_argument('--approve', required=True, help='明确批准已审阅方案的 approvalDigest')
        elif name == 'verify':
            command.add_argument('--complete', action='store_true', help='所有手机检查通过后保存完成状态')
    args = parser.parse_args(argv)
    try:
        adb = Adb(find_adb(args.adb))
        selected = args.serial or os.environ.get('ANDROID_SERIAL')
        if args.command == 'scan':
            result = scan(adb, selected)
        elif args.command == 'plan':
            snapshot = scan(adb, selected)
            identity = {key: value for key, value in {'companionName': args.name, 'userAddress': args.address,
                                                      'personality': args.personality}.items() if value is not None}
            shizuku_apk = apk_info(args.shizuku_apk, SHIZUKU_PACKAGE) if args.shizuku_apk else None
            if shizuku_apk and (not args.shizuku_sha256 or args.shizuku_sha256.lower() != shizuku_apk['sha256']):
                raise SetupError('安装 Shizuku 前需提供与可信发布信息一致的 --shizuku-sha256')
            branding = {'variant': 'personal', 'signingContinuityConfirmed': args.signing_continuity_confirmed} if args.personal_build else {'variant': 'standard'}
            if args.app_name is not None:
                branding['appName'] = args.app_name
            if args.app_icon is not None:
                with open(args.app_icon, 'rb') as stream:
                    if stream.read(8) != b'\x89PNG\r\n\x1a\n':
                        raise SetupError('主应用图标必须为 PNG')
                branding['appIconSha256'] = file_sha256(args.app_icon)
            result = create_plan(snapshot, apk_info(args.apk), mode=args.mode,
                                 capabilities=parse_capabilities(args.capability), identity=identity,
                                 automatic_permissions=args.permissions == 'automatic', extra_permissions=args.system_permission,
                                 shizuku_choice=args.shizuku, shizuku_apk=shizuku_apk, branding=branding)
            destination = Path(args.output).expanduser()
            if destination.exists():
                raise SetupError('方案文件已存在；请使用新文件名，避免覆盖已批准方案')
            with destination.open('x', encoding='utf-8') as stream:
                json.dump(result, stream, ensure_ascii=False, indent=2)
                stream.write('\n')
        elif args.command == 'apply':
            result = apply_plan(adb, read_json(args.plan), args.approve, selected)
        else:
            result = verify(adb, selected, args.complete)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result.get('ok', True) else 1
    except (SetupError, AdbError, ValueError, OSError) as error:
        # Errors from ADB never include raw device output or credential values.
        message = str(error) if isinstance(error, (SetupError, AdbError)) else '安装输入或本地文件无效，请重新核对方案'
        print(json.dumps({'schemaVersion': SCHEMA, 'ok': False, 'error': message}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    sys.exit(main())
