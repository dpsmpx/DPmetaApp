"""Подключённый аккаунт: настоящий credential helper Git, без HTTP и реальных ключей."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'tools/shared_verify/runtime.py'
SPEC = importlib.util.spec_from_file_location('verify_account', SOURCE)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)
SYNTHETIC = ''.join(('synthetic', '-', 'account', '-', 'value'))


class SharedVerifyAccountTest(unittest.TestCase):
    def environment(self, action='fetch'):
        with patch.object(runtime, 'ACCOUNT', SYNTHETIC):
            return runtime.git_environment((action,))

    def helper(self, request, operation='get'):
        return subprocess.run([sys.executable, str(SOURCE), 'credential', operation],
                              input=request, text=True, capture_output=True,
                              env={**os.environ, runtime.TOKEN_ENV: SYNTHETIC}, check=True).stdout

    def test_helper_only_serves_exact_repository(self):
        answer = self.helper('protocol=https\nhost=github.com\npath=dpsmpx/DP-Verify.git\n\n')
        self.assertEqual(answer, 'username=x-access-token\npassword=' + SYNTHETIC + '\n\n')
        for request in ('protocol=http\nhost=github.com\npath=dpsmpx/DP-Verify.git\n',
                        'protocol=https\nhost=example.invalid\npath=dpsmpx/DP-Verify.git\n',
                        'protocol=https\nhost=github.com.evil.invalid\npath=dpsmpx/DP-Verify.git\n',
                        'protocol=https\nhost=github.com\npath=dpsmpx/other.git\n',
                        'protocol=https\nhost=github.com\n'):
            with self.subTest(request=request):
                self.assertEqual(self.helper(request), '')

    def test_store_and_erase_do_not_output_or_persist_account(self):
        for operation in ('store', 'erase'):
            self.assertEqual(self.helper('password=' + SYNTHETIC + '\n', operation), '')

    def test_git_itself_calls_scoped_helper(self):
        result = subprocess.run(['git', 'credential', 'fill'], text=True,
                                input='protocol=https\nhost=github.com\npath=dpsmpx/DP-Verify.git\n\n',
                                capture_output=True, env=self.environment(), check=True)
        self.assertIn('password=' + SYNTHETIC, result.stdout)
        self.assertEqual(result.stderr, '')

    def test_git_denies_other_repository_without_prompt(self):
        result = subprocess.run(['git', 'credential', 'fill'], text=True,
                                input='protocol=https\nhost=github.com\npath=dpsmpx/other.git\n\n',
                                capture_output=True, env=self.environment())
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(SYNTHETIC, result.stdout + result.stderr)

    def test_only_network_commands_receive_credentials(self):
        for action in ('clone', 'fetch', 'push'):
            self.assertEqual(self.environment(action)[runtime.TOKEN_ENV], SYNTHETIC)
        for action in ('commit', 'add', 'var', 'worktree', 'status', 'rev-parse'):
            self.assertNotIn(runtime.TOKEN_ENV, self.environment(action))
        self.assertNotIn(runtime.TOKEN_ENV, os.environ)

    def test_redirects_disabled_and_previous_helpers_reset(self):
        env = self.environment()
        options = [(env['GIT_CONFIG_KEY_' + str(i)], env['GIT_CONFIG_VALUE_' + str(i)])
                   for i in range(int(env['GIT_CONFIG_COUNT']))]
        self.assertIn(('http.followRedirects', 'false'), options)
        self.assertIn(('credential.helper', ''), options)
        self.assertIn(('credential.useHttpPath', 'true'), options)
        self.assertNotIn(SYNTHETIC, repr(options))

    def test_account_read_is_bounded_and_rejects_protocol_injection(self):
        for value in (None, {}, {'token': ''}, {'token': 'x\npassword=y'}, {'token': 'a' * 4097}, {'token': 4}):
            with self.subTest(value=type(value).__name__), patch('sys.stdin', io.StringIO(json.dumps(value))):
                with self.assertRaises(runtime.Refused):
                    runtime.read_account()
        with patch('sys.stdin', io.StringIO(json.dumps({'token': SYNTHETIC}))):
            self.assertEqual(runtime.read_account(), SYNTHETIC)

    def test_author_is_automation_not_impersonated_user(self):
        env = self.environment('commit')
        self.assertEqual(env['GIT_AUTHOR_NAME'], 'DPmetaApp')
        self.assertEqual(env['GIT_AUTHOR_EMAIL'], 'dpmetaapp@localhost')


if __name__ == '__main__':
    unittest.main()
