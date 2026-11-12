import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(directory, 'phone-lab.html'), 'utf8');

// Execute the page's real handlers against a small DOM stand-in. Actual Android
// accessibility, layout and browser behaviour are tested separately on the phone.
function page() {
  const elements = new Map();
  for (const match of html.matchAll(/\bid="([^"]+)"/g)) {
    elements.set(match[1], {
      value: '', textContent: '', hidden: match[1] === 'delayed-button', disabled: false,
      children: [], handlers: new Map(),
      append(child) { this.children.push(child); },
      addEventListener(event, handler) { this.handlers.set(event, handler); },
    });
  }
  const timers = [];
  const document = {
    getElementById: id => { assert.ok(elements.has(id), `Missing element ${id}`); return elements.get(id); },
    createElement: () => ({ textContent: '' }),
  };
  const context = vm.createContext({ document, setTimeout: (handler, ms) => { timers.push({ handler, ms }); return timers.length; }, clearTimeout() {} });
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script);
  vm.runInContext(script, context);
  return {
    elements, timers,
    get: id => document.getElementById(id),
    fire: (id, event = 'click') => document.getElementById(id).handlers.get(event)({ preventDefault() {} }),
  };
}

test('form refuses missing fields and displays all saved values as text', () => {
  const p = page();
  p.fire('profile-form', 'submit');
  assert.match(p.get('form-result').textContent, /表单未保存/);
  p.get('person-name').value = '测试同学';
  p.get('city-name').value = '上海';
  p.get('study-goal').value = '每天练习三题';
  p.fire('profile-form', 'submit');
  assert.equal(p.get('form-result').textContent, '表单已保存：姓名 测试同学；城市 上海；学习目标 每天练习三题');
});

test('delay button appears after the scheduled three seconds, then verifies its click', () => {
  const p = page();
  p.fire('start-delay');
  assert.equal(p.get('delayed-button').hidden, true);
  assert.equal(p.timers.length, 1);
  assert.equal(p.timers[0].ms, 3000);
  p.timers[0].handler();
  assert.equal(p.get('delayed-button').hidden, false);
  assert.equal(p.get('delay-result').textContent, '延迟按钮已出现');
  p.fire('delayed-button');
  assert.equal(p.get('delay-result').textContent, '延迟按钮已点击');
});

test('long list has forty ordered records and exactly one target at 31', () => {
  const rows = page().get('long-list').children;
  assert.equal(rows.length, 40);
  assert.equal(rows.filter(row => row.textContent.includes('目标蓝鲸')).length, 1);
  assert.match(rows[30].textContent, /^测试记录 31：目标蓝鲸$/);
});

test('simulated chat refuses blank drafts and appends one text bubble without HTML evaluation', () => {
  const p = page();
  p.fire('send-message');
  assert.equal(p.get('messages').children.length, 0);
  assert.match(p.get('chat-result').textContent, /未发送/);
  p.get('chat-draft').value = '<img src=x onerror=alert(1)>明天九点';
  p.fire('send-message');
  assert.equal(p.get('messages').children.length, 1);
  assert.equal(p.get('messages').children[0].textContent, '我：<img src=x onerror=alert(1)>明天九点');
  assert.equal(p.get('chat-draft').value, '');
  assert.match(p.get('chat-result').textContent, /已发送 1 条.*测试同学小鲸/);
  p.fire('send-message');
  assert.equal(p.get('messages').children.length, 1);
});

test('repeated check-in does not count twice and reset allows a fresh trial', () => {
  const p = page();
  p.fire('checkin-button');
  assert.match(p.get('checkin-result').textContent, /累计测试签到 1 次/);
  assert.equal(p.get('checkin-button').disabled, true);
  p.fire('checkin-button');
  assert.equal(p.get('checkin-result').textContent, '今日已签到，无需重复');
  p.fire('reset-checkin');
  assert.equal(p.get('checkin-button').disabled, false);
  p.fire('checkin-button');
  assert.match(p.get('checkin-result').textContent, /累计测试签到 1 次/);
});

test('quiz distinguishes partial answers from all three correct answers', () => {
  const p = page();
  p.get('answer-one').value = '12';
  p.get('answer-two').value = '4';
  p.fire('quiz-form', 'submit');
  assert.match(p.get('quiz-result').textContent, /答对 1\/3 题.*第1题正确；第2题错误；第3题错误/);
  p.get('answer-two').value = '3';
  p.get('answer-three').value = '100';
  p.fire('quiz-form', 'submit');
  assert.match(p.get('quiz-result').textContent, /答对 3\/3 题/);
});

test('skill installation previews without writing, installs idempotently and preserves edits', t => {
  const candidates = process.platform === 'win32'
    ? [process.env.PHONE_ROUTINES_BASH, 'D:/git/Git/bin/bash.exe', 'C:/Program Files/Git/bin/bash.exe'].filter(Boolean)
    : [process.env.PHONE_ROUTINES_BASH || '/bin/sh'];
  const shell = candidates.find(candidate => fs.existsSync(candidate));
  if (!shell) return t.skip('POSIX shell unavailable; installer not executed');
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-phone-skill-'));
  t.after(() => fs.rmSync(scratch, { recursive: true, force: true }));
  const shellPath = value => process.platform === 'win32'
    ? value.replaceAll('\\', '/').replace(/^([a-zA-Z]):/, (_, drive) => `/${drive.toLowerCase()}`)
    : value;
  const run = args => spawnSync(shell, [shellPath(path.join(directory, 'install.sh')), ...args], { encoding: 'utf8' });
  assert.notEqual(run([]).status, 0);
  const args = ['--dsh-home', shellPath(scratch)];
  const destination = path.join(scratch, 'skills', 'dsh-phone-routines');
  const preview = run([...args, '--dry-run']);
  assert.equal(preview.status, 0, preview.stderr);
  assert.equal(fs.existsSync(path.join(scratch, 'skills')), false);
  const installed = run(args);
  assert.equal(installed.status, 0, installed.stderr);
  const entry = path.join(destination, 'SKILL.md');
  const original = fs.readFileSync(entry, 'utf8');
  assert.match(original, /name: dsh-phone-routines/);
  assert.ok(fs.existsSync(path.join(destination, 'routines.md')));
  const unchanged = run(args);
  assert.equal(unchanged.status, 0, unchanged.stderr);
  assert.match(unchanged.stdout, /Unchanged/);
  fs.writeFileSync(entry, `${original}\n用户修改\n`);
  const refused = run(args);
  assert.notEqual(refused.status, 0);
  assert.match(fs.readFileSync(entry, 'utf8'), /用户修改/);
});
