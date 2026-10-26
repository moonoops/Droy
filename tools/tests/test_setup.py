"""USB installation contracts use synthetic APKs and ADB fixtures only."""
import base64
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location('droy_setup', TOOLS / 'setup.py')
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FakeAdb:
    def __init__(self, installed=False, version=4, user='0', serial='emulator-5554'):
        self.serial = None
        self.device_serial = serial
        self.installed = installed
        self.version = version
        self.user = user
        self.calls = []
        self.install_output = 'Success'
        self.install_failure = False
        self.changed_at = '2026-10-26 00:00:00'
        self.shizuku_installed = False
        self.shizuku_version = '13.6.0.r1086.2650830c'
        self.shizuku_installation = '/data/app/~~SyntheticToken==/moe.shizuku.privileged.api-SyntheticInstall=='
        self.shizuku_native_directory = None
        self.shizuku_primary_abi = 'arm64-v8a'
        self.shizuku_dump_override = None
        self.shizuku_file_available = True
        self.shizuku_start_failure = False
        self.setup_state = {'schemaVersion': 1, 'required': True, 'completed': False, 'step': 1,
                            'source': 'phone', 'capabilities': {}, 'canComplete': False,
                            'checks': [{'id': 'model', 'status': 'pending', 'required': True}]}

    def run(self, *args, **kwargs):
        self.calls.append(args)
        if args == ('devices',):
            return 'List of devices attached\n' + self.device_serial + '\tdevice'
        if args == ('get-state',):
            return 'device'
        if args[0] == 'install':
            if self.install_failure:
                raise MODULE.AdbError('ADB 执行失败，退出码 1')
            self.installed = True
            return self.install_output
        raise AssertionError(args)

    def shell(self, *args, **kwargs):
        self.calls.append(('shell',) + args)
        if args[:1] == ('getprop',):
            return {'ro.build.version.sdk': '36', 'ro.product.cpu.abilist': 'arm64-v8a,armeabi-v7a',
                    'ro.product.model': 'Synthetic emulator', 'ro.product.manufacturer': 'Fixture'}.get(args[1], '')
        if args == ('am', 'get-current-user'):
            return self.user
        if args[:2] == ('pm', 'path'):
            if args[-1] == MODULE.SHIZUKU_PACKAGE:
                return 'package:' + self.shizuku_installation + '/base.apk' if self.shizuku_installed else ''
            return 'package:/data/app/fixture/base.apk' if args[-1] == MODULE.PACKAGE and self.installed else ''
        if args[:2] == ('dumpsys', 'package'):
            if args[-1] == MODULE.SHIZUKU_PACKAGE:
                if self.shizuku_dump_override is not None:
                    return self.shizuku_dump_override
                result = ('Packages:\n  Package [moe.shizuku.privileged.api] (fixture):\n'
                          + '    codePath=' + self.shizuku_installation + '\n'
                          + '    legacyNativeLibraryDir=' + self.shizuku_installation + '/lib\n'
                          + '    primaryCpuAbi=' + self.shizuku_primary_abi + '\n'
                          + '    versionName=' + self.shizuku_version + '\n')
                if self.shizuku_native_directory is not None:
                    result += '    nativeLibraryDir=' + self.shizuku_native_directory + '\n'
                return result
            return 'versionCode=' + str(self.version) + '\nversionName=0.3.0\nlastUpdateTime=' + self.changed_at
        if args == ('dumpsys', 'webviewupdate'):
            return 'Current WebView package (name, version): (com.android.webview, 143.0.0)'
        if args[:1] == ('df',):
            return 'Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fixture 5000000 1000 4900000 1% /data'
        if args[:1] == ('settings',):
            return 'null'
        if args[:2] == ('sh', '-c'):
            return 'unknown'
        if args[:1] == ('test',):
            if not self.shizuku_file_available:
                raise MODULE.AdbError('ADB 执行失败，退出码 1')
            return ''
        if args[0].endswith('/libshizuku.so') or args[:1] == ('sh',) and len(args) > 1 and args[1].endswith('/start.sh'):
            if self.shizuku_start_failure:
                raise MODULE.AdbError('ADB 执行失败，退出码 1')
            return 'starter request submitted'
        if args[:2] == ('content', 'call'):
            method = args[args.index('--method') + 1]
            if method == 'setup-configure':
                encoded = args[args.index('--arg') + 1]
                config = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
                self.setup_state.update(config)
            elif method == 'setup-complete':
                if self.setup_state['canComplete']:
                    self.setup_state['completed'] = True
                else:
                    raise MODULE.AdbError('not complete')
            return 'Result: Bundle[{setupJson=' + json.dumps(self.setup_state, ensure_ascii=False) + '}]'
        raise AssertionError(args)


class FakePermissionSetup:
    instances = []

    def __init__(self, adb, user, api, package_name):
        self.adb, self.user, self.api = adb, user, api
        self.package = package_name
        self.permissions = None
        self.instances.append(self)

    def open_app(self):
        self.adb.calls.append(('open-app',))

    def apply_selected(self, permissions):
        self.permissions = permissions
        return []


class SetupTests(unittest.TestCase):
    def test_dialogue_and_enabled_phone_capability_are_rejected_before_installation(self):
        with self.assertRaises(MODULE.SetupError):
            self.plan(mode='dialogue', capabilities={'allowContacts': True})

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='droy setup ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / 'Droy fixture.apk'
        self.make_apk(self.apk)
        self.adb = FakeAdb()
        FakePermissionSetup.instances.clear()
        self.addCleanup(patch.stopall)
        patch.object(MODULE, 'PermissionSetup', FakePermissionSetup).start()

    def make_apk(self, path, package=MODULE.PACKAGE, version=4, minimum=30):
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="' + package + '" android:versionCode="' + str(version) + '" android:versionName="0.3.0"><uses-sdk android:minSdkVersion="' + str(minimum) + '"/></manifest>')
            archive.writestr('classes.dex', b'synthetic fixture, not executable')

    def plan(self, **kwargs):
        return MODULE.create_plan(MODULE.scan(self.adb), MODULE.apk_info(self.apk), **kwargs)

    def test_scan_is_read_only_and_never_requests_root(self):
        result = MODULE.scan(self.adb)
        self.assertTrue(result['compatible'])
        self.assertEqual(result['root']['authorized'], 'unknown')
        self.assertEqual(result['webView']['package'], 'com.android.webview')
        self.assertFalse(any(call[0] == 'install' or call[:2] == ('shell', 'su') or 'grant' in call or 'put' in call for call in self.adb.calls))

    def test_first_plan_recommends_only_basic_phone_capabilities(self):
        plan = self.plan(automatic_permissions=True)
        self.assertEqual(plan['configuration']['mode'], 'assistant')
        self.assertEqual({key for key, enabled in plan['configuration']['capabilities'].items() if enabled}, MODULE.ASSISTANT_CAPABILITIES)
        self.assertEqual(plan['permissions'], ['accessibility'])
        self.assertEqual(plan['modelSetup'], 'phone')
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_first_install_with_explicit_capability_selects_custom_mode(self):
        plan = self.plan(capabilities={'allowContacts': True}, automatic_permissions=True)
        self.assertEqual(plan['configuration']['mode'], 'custom')
        self.assertEqual({name for name, value in plan['configuration']['capabilities'].items() if value}, {'allowContacts'})
        self.assertEqual(plan['permissions'], ['contacts'])

    def test_upgrade_without_new_choice_preserves_capabilities_and_permissions(self):
        self.adb.installed = True
        self.adb.setup_state['capabilities'] = {'allowObserve': False, 'allowControl': False}
        plan = self.plan(automatic_permissions=True)
        self.assertEqual(plan['configuration'], {'source': 'usb'})
        self.assertEqual(plan['permissions'], [])
        result = MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertEqual(result['status'], 'pending_phone')
        self.assertEqual(self.adb.setup_state['capabilities'], {'allowObserve': False, 'allowControl': False})

    def test_dialogue_finishes_host_part_without_requiring_device_permissions(self):
        plan = self.plan(mode='dialogue', automatic_permissions=True, identity={'companionName': '小墨', 'userAddress': '测试用户', 'personality': '简洁友善'})
        result = MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertEqual(result['status'], 'pending_phone')
        self.assertEqual(FakePermissionSetup.instances[-1].permissions, [])
        self.assertEqual(FakePermissionSetup.instances[-1].package, "io.droyagent.app")
        self.assertFalse(any(self.adb.setup_state['capabilities'].values()))
        self.assertEqual(self.adb.setup_state['userAddress'], '测试用户')
        self.assertFalse(any('setup-complete' in call for call in self.adb.calls))

    def test_approval_required_before_mutation(self):
        plan = self.plan()
        self.adb.calls.clear()
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, 'not-approved')
        self.assertEqual(self.adb.calls, [])

    def test_unknown_plan_field_rejected_even_with_recomputed_digest(self):
        plan = self.plan()
        plan['shellCommand'] = 'su -c anything'
        unsigned = dict(plan)
        unsigned.pop('approvalDigest')
        plan['approvalDigest'] = MODULE.digest(unsigned)
        with self.assertRaises(MODULE.SetupError):
            MODULE.validate_plan(plan)

    def test_credentials_and_forged_verification_not_allowed(self):
        for fields in ({'apiKey': 'fictional-not-a-key'}, {'completed': True}, {'modelVerification': {'passed': True}}):
            with self.assertRaises(MODULE.SetupError):
                self.plan(identity=fields)

    def test_plan_tampering_rejected_before_adb(self):
        plan = self.plan()
        plan['identity']['userAddress'] = 'changed after approval'
        self.adb.calls.clear()
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertEqual(self.adb.calls, [])

    def test_different_serial_or_user_never_installs(self):
        plan = self.plan()
        for override in ('emulator-5556',):
            with self.assertRaises(MODULE.SetupError):
                MODULE.apply_plan(self.adb, plan, plan['approvalDigest'], override)
        self.adb.user = '10'
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_changed_existing_version_requires_new_plan(self):
        self.adb.installed = True
        self.adb.version = 3
        plan = self.plan()
        self.adb.version = 4
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_changed_user_choices_require_new_plan(self):
        self.adb.installed = True
        self.adb.setup_state['capabilities'] = {'allowObserve': True}
        plan = self.plan(mode='assistant', automatic_permissions=True)
        self.adb.setup_state['capabilities']['allowObserve'] = False
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_changed_apk_never_installs(self):
        plan = self.plan()
        self.make_apk(self.apk, version=5)
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_wrong_package_and_downgrade_rejected(self):
        self.make_apk(self.apk, package='fixture.unrelated.app')
        with self.assertRaises(MODULE.SetupError):
            MODULE.apk_info(self.apk)
        self.make_apk(self.apk, version=2)
        self.adb.installed = True
        with self.assertRaises(MODULE.SetupError):
            self.plan()

    def test_signature_install_failure_does_not_uninstall_or_expose_output(self):
        plan = self.plan()
        self.adb.install_failure = True
        with self.assertRaisesRegex(MODULE.SetupError, '没有卸载'):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertFalse(any('uninstall' in call or 'clear' in call for call in self.adb.calls))

    def test_only_selected_extra_permissions_added(self):
        plan = self.plan(mode='custom', capabilities={'allowContacts': True}, automatic_permissions=True,
                         extra_permissions=['notifications'])
        self.assertEqual(plan['permissions'], ['contacts', 'notifications'])
        MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertEqual(FakePermissionSetup.instances[-1].permissions, ['contacts', 'notifications'])

    def test_recomputed_but_unselected_permission_rejected(self):
        plan = self.plan(mode='dialogue')
        plan['permissions'] = ['microphone']
        unsigned = dict(plan)
        unsigned.pop('approvalDigest')
        plan['approvalDigest'] = MODULE.digest(unsigned)
        with self.assertRaisesRegex(MODULE.SetupError, '权限不匹配'):
            MODULE.validate_plan(plan)

    def test_complete_only_after_native_checks_pass(self):
        self.adb.installed = True
        result = MODULE.verify(self.adb, complete=True)
        self.assertEqual(result['status'], 'pending_phone')
        self.adb.setup_state['canComplete'] = True
        result = MODULE.verify(self.adb, complete=True)
        self.assertEqual(result['status'], 'complete')

    def test_past_completion_does_not_hide_current_failed_verification(self):
        self.adb.installed = True
        self.adb.setup_state['completed'] = True
        self.adb.setup_state['canComplete'] = False
        result = MODULE.verify(self.adb, complete=True)
        self.assertEqual(result['status'], 'pending_phone')
        self.assertFalse(any('setup-complete' in call for call in self.adb.calls))

    def test_user_address_limit_matches_phone_before_any_install(self):
        accepted = self.plan(identity={'userAddress': '称' * 40})
        self.assertEqual(len(accepted['identity']['userAddress']), 40)
        with self.assertRaises(MODULE.SetupError):
            self.plan(identity={'userAddress': '称' * 41})
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_multiline_personality_matches_native_control_character_rules(self):
        plan = self.plan(identity={'personality': '  简洁友善\n\t先说明结果  '})
        self.assertEqual(plan['identity']['personality'], '简洁友善\n\t先说明结果')
        for key, value in [('userAddress', '称呼\n第二行'), ('personality', '有\r内部换行'),
                           ('personality', '包含' + chr(127)), ('userAddress', '包含' + chr(159))]:
            with self.assertRaises(MODULE.SetupError):
                self.plan(identity={key: value})

    def test_capabilities_only_upgrade_preserves_pause_and_explains_phone_resume(self):
        self.adb.installed = True
        self.adb.setup_state.update(mode='dialogue', toolsPaused=True, capabilities={'allowCalendar': False})
        plan = self.plan(capabilities={'allowCalendar': True}, automatic_permissions=True)
        self.assertNotIn('mode', plan['configuration'])
        self.assertNotIn('toolsPaused', plan['configuration'])
        self.assertEqual(plan['configuration']['capabilities'], {'allowCalendar': True})
        self.assertTrue(any('主动恢复' in text for text in plan['phoneActions']))
        result = MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertTrue(self.adb.setup_state['toolsPaused'])
        self.assertEqual(result['status'], 'pending_phone')

    def test_pause_changed_after_review_requires_new_plan(self):
        self.adb.installed = True
        self.adb.setup_state['toolsPaused'] = False
        plan = self.plan()
        self.adb.setup_state['toolsPaused'] = True
        with self.assertRaises(MODULE.SetupError):
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertFalse(any(call[0] == 'install' for call in self.adb.calls))

    def test_explicit_shizuku_skip_replaces_previously_selected_use(self):
        self.adb.installed = True
        self.adb.setup_state['shizukuChoice'] = 'use'
        plan = self.plan(shizuku_choice='skip')
        self.assertEqual(plan['configuration']['shizukuChoice'], 'skip')
        self.assertEqual(plan['shizuku'], {'choice': 'skip', 'action': 'skip', 'apk': None})
        MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertEqual(self.adb.setup_state['shizukuChoice'], 'skip')
        self.assertFalse(any(call[:2] == ('shell', 'sh') and len(call) > 2 and call[2].startswith('/storage/') for call in self.adb.calls))

    def test_upgrade_without_shizuku_argument_preserves_previous_choice_without_restart(self):
        self.adb.installed = True
        for previous_choice in ('use', 'install'):
            self.adb.setup_state['shizukuChoice'] = previous_choice
            plan = self.plan()
            self.assertNotIn('shizukuChoice', plan['configuration'])
            self.assertEqual(plan['shizuku'], {'choice': previous_choice, 'action': 'preserve', 'apk': None})
            MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
            self.assertEqual(self.adb.setup_state['shizukuChoice'], previous_choice)
        self.assertFalse(any(call[:2] == ('shell', 'sh') and len(call) > 2 and call[2].startswith('/storage/') for call in self.adb.calls))

    def test_new_install_without_shizuku_argument_defaults_to_skip(self):
        plan = self.plan()
        self.assertEqual(plan['configuration']['shizukuChoice'], 'skip')
        self.assertEqual(plan['shizuku'], {'choice': 'skip', 'action': 'skip', 'apk': None})

    def test_provider_invalid_response_never_becomes_success(self):
        with patch.object(self.adb, 'shell', return_value='Result: Bundle[{error=private-output}]'):
            with self.assertRaisesRegex(MODULE.SetupError, '未返回安装状态'):
                MODULE.provider(self.adb, '0', 'setup-verify')

    def test_shizuku_install_requires_explicit_component_package(self):
        with self.assertRaises(MODULE.SetupError):
            self.plan(shizuku_choice='install')
        with self.assertRaises(MODULE.SetupError):
            self.plan(shizuku_apk=MODULE.apk_info(self.apk))

    def test_shizuku_13_6_uses_installed_native_starter_without_native_library_field(self):
        self.adb.shizuku_installed = True
        expected = self.adb.shizuku_installation + '/lib/arm64/libshizuku.so'
        command = MODULE.shizuku_start_command(self.adb, '0')
        self.assertEqual(command, (expected,))
        self.assertIn(('shell', 'test', '-x', expected), self.adb.calls)
        self.assertFalse(any('start.sh' in ' '.join(call) for call in self.adb.calls))

    def test_shizuku_13_6_maps_installed_abi_to_library_directory(self):
        self.adb.shizuku_installed = True
        self.adb.shizuku_primary_abi = 'x86_64'
        self.adb.shizuku_native_directory = self.adb.shizuku_installation + '/lib/x86_64'
        self.assertEqual(MODULE.shizuku_start_command(self.adb, '0'),
                         (self.adb.shizuku_installation + '/lib/x86_64/libshizuku.so',))

    def test_shizuku_13_5_retains_its_documented_external_script(self):
        self.adb.shizuku_installed = True
        self.adb.shizuku_version = '13.5.4.r1049.0e53409'
        expected = '/storage/emulated/10/Android/data/moe.shizuku.privileged.api/start.sh'
        self.assertEqual(MODULE.shizuku_start_command(self.adb, '10'), ('sh', expected))
        self.assertIn(('shell', 'test', '-r', expected), self.adb.calls)
        self.assertFalse(any('libshizuku.so' in ' '.join(call) for call in self.adb.calls))

    def test_shizuku_rejects_unknown_version_without_path_retry(self):
        self.adb.shizuku_installed = True
        self.adb.shizuku_version = '13.7.0.r9999.fixture'
        with self.assertRaisesRegex(MODULE.SetupError, '尚未验证'):
            MODULE.shizuku_start_command(self.adb, '0')
        self.assertFalse(any(call[:2] == ('shell', 'test') for call in self.adb.calls))

    def test_shizuku_rejects_untrusted_or_conflicting_installation_paths(self):
        self.adb.shizuku_installed = True
        for invalid in ('/data/local/tmp/evil', '/data/app/~~token/other.package-abc',
                        '/data/app/~~token/moe.shizuku.privileged.api-abc/../other',
                        '/data/app/~~token/moe.shizuku.privileged.api-abc;id'):
            self.adb.shizuku_installation = invalid
            with self.assertRaises(MODULE.SetupError):
                MODULE.shizuku_start_command(self.adb, '0')
        self.adb.shizuku_installation = '/data/app/~~token/moe.shizuku.privileged.api-abc'
        self.adb.shizuku_native_directory = '/data/local/tmp/evil'
        with self.assertRaisesRegex(MODULE.SetupError, '原生目录'):
            MODULE.shizuku_start_command(self.adb, '0')
        self.assertFalse(any(call[:2] == ('shell', 'test') for call in self.adb.calls))

    def test_shizuku_package_block_cannot_borrow_another_packages_directory(self):
        self.adb.shizuku_installed = True
        self.adb.shizuku_dump_override = ('Packages:\n  Package [moe.shizuku.privileged.api] (fixture):\n'
                                         + '    versionName=13.6.0.r1086.2650830c\n'
                                         + '  Package [fixture.other] (fixture):\n'
                                         + '    codePath=' + self.adb.shizuku_installation + '\n')
        with self.assertRaisesRegex(MODULE.SetupError, '包管理器不一致'):
            MODULE.shizuku_start_command(self.adb, '0')
        self.assertFalse(any(call[:2] == ('shell', 'test') for call in self.adb.calls))

    def test_shizuku_successful_start_remains_pending_until_native_verifies(self):
        self.adb.shizuku_installed = True
        plan = self.plan(shizuku_choice='use')
        result = MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
        self.assertEqual(result['status'], 'pending_phone')
        self.assertEqual(result['components'], [{'name': 'Shizuku 启动请求', 'status': 'pending_verification',
                                                'detail': '仍需检查服务运行，并在手机允许 Droy 使用 Shizuku'}])
        self.assertFalse(any('setup-complete' in call for call in self.adb.calls))

    def test_shizuku_missing_starter_or_launch_failure_cannot_claim_complete(self):
        for missing in (True, False):
            self.adb = FakeAdb()
            self.adb.shizuku_installed = True
            self.adb.shizuku_file_available = not missing
            self.adb.shizuku_start_failure = not missing
            self.adb.setup_state['canComplete'] = True
            plan = self.plan(shizuku_choice='use')
            result = MODULE.apply_plan(self.adb, plan, plan['approvalDigest'])
            self.assertFalse(result['ok'])
            self.assertEqual(result['status'], 'needs_action')
            self.assertEqual(result['components'][0]['status'], 'needs_action')
            self.assertFalse(any('setup-complete' in call for call in self.adb.calls))
            self.assertFalse(any('start.sh' in ' '.join(call) for call in self.adb.calls))

    def test_personal_build_requires_signing_continuity_confirmation(self):
        with self.assertRaisesRegex(MODULE.SetupError, '同一签名'):
            self.plan(branding={'variant': 'personal'})
        self.plan(branding={'variant': 'personal', 'signingContinuityConfirmed': True})


if __name__ == '__main__':
    unittest.main()
