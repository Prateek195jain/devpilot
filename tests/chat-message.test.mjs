import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
const require = createRequire(import.meta.url);

const source = readFileSync(new URL('../components/devpilot/chat-message.tsx', import.meta.url), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React,
} }).outputText;
const { ChatMessage, ChatPendingReply } = await import('data:text/javascript;base64,' +
    Buffer.from("import React from '" + new URL('../node_modules/react/index.js', import.meta.url).href + "';\n" + js).toString('base64'));
const render = message => renderToStaticMarkup(React.createElement(ChatMessage, { message }));
const base = { id: 'm', role: 'assistant', content: 'Repository answer', createdAt: '' };
test('assistant renders sources with one entry per path', () => {
    const html = render({ ...base, sources: [
        { sourcePath: 'A.java', chunkIndex: 0, commitSha: 'a'.repeat(40) },
        { sourcePath: 'A.java', chunkIndex: 1, commitSha: 'a'.repeat(40) },
        { sourcePath: 'B.java', chunkIndex: 0, commitSha: 'a'.repeat(40) },
    ] });
    assert.match(html, /Repository answer/);
    assert.match(html, /Repository sources/);
    assert.equal((html.match(/A.java/g) ?? []).length, 1);
    assert.match(html, /B.java/);
    assert.ok(html.indexOf('A.java') < html.indexOf('B.java'));
});
test('legacy and empty source messages render normally', () => {
    for (const sources of [undefined, []]) {
        const html = render({ ...base, sources });
        assert.match(html, /Repository answer/);
        assert.doesNotMatch(html, /Repository sources/);
    }
});
test('user messages hide sources and source paths are escaped as text', () => {
    const sources = [{ sourcePath: '<script>alert(1)</script>', chunkIndex: 0, commitSha: '' }];
    assert.doesNotMatch(render({ ...base, role: 'user', sources }), /Repository sources/);
    const html = render({ ...base, sources });
    assert.doesNotMatch(html, /<script>/);
    assert.match(html, /&lt;script&gt;/);
});

// Exercise the actual screen and its event handlers using lightweight hook/UI
// boundaries, following the existing startup rendering tests without new dependencies.
function compileScreen(relative, boundaries) {
    const source = readFileSync(new URL(relative, import.meta.url), 'utf8');
    const js = ts.transpileModule(source, { compilerOptions: {
        module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
        jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    } }).outputText;
    const compiled = { exports: {} };
    new Function('require', 'module', 'exports', js)(
        name => boundaries[name] ?? require(name), compiled, compiled.exports);
    return compiled.exports;
}

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function descendants(node) {
    if (Array.isArray(node)) return node.flatMap(descendants);
    if (!React.isValidElement(node)) return [];
    return [node, ...descendants(node.props.children)];
}

function chatHarness() {
    let cursor = 0;
    const cells = [];
    const hooks = {
        ...React,
        useState(initial) {
            const index = cursor++;
            cells[index] ??= { value: typeof initial === 'function' ? initial() : initial };
            return [cells[index].value, update => {
                cells[index].value = typeof update === 'function' ? update(cells[index].value) : update;
            }];
        },
        useRef(initial) {
            const index = cursor++;
            cells[index] ??= { value: { current: initial } };
            return cells[index].value;
        },
        useEffect() {},
    };
    const errors = [];
    const work = deferred();
    const calls = [];
    const resource = data => ({ data, loading: false, error: '', reload() {} });
    const messages = resource({ content: [], totalElements: 0, totalPages: 0 });
    const Button = ({ size, variant, ...props }) => {
        void size; void variant; return React.createElement('button', props);
    };
    const Textarea = props => React.createElement('textarea', props);
    const ui = {
        Badge: ({ children }) => React.createElement('span', null, children),
        EmptyState: ({ title }) => React.createElement('div', null, title),
        Loading: () => React.createElement('div', { role: 'status' }, 'Loading history'),
        Pager: () => React.createElement('button', null, 'Next'),
    };
    const shared = compileScreen('../components/devpilot/screens/shared.tsx', {
        react: hooks, '@/components/ui/button': { Button },
        '@/lib/devpilot-api': {}, 'lucide-react': { CheckCircle2: () => null },
        sonner: { toast: { error: error => errors.push(error), success() {} } },
        '../ui': ui,
    });
    const { Chat } = compileScreen('../components/devpilot/screens/Chat.tsx', {
        react: hooks,
        '../indexing-status': { IndexingStatus: () => null },
        '../chat-message': { ChatMessage, ChatPendingReply },
        '@/components/ui/button': { Button }, '@/components/ui/textarea': { Textarea },
        '@/lib/devpilot-api': {
            repoApi: (id, path) => '/repositories/' + id + path,
            api: async () => ({ id: 'c' }),
            sendChatMessage: (...args) => { calls.push(args); return work.promise; },
        },
        'lucide-react': Object.fromEntries(['ArrowRight', 'BrainCircuit', 'Plus', 'Send', 'Sparkles'].map(name => [name, () => null])),
        '../ui': ui, './shared': shared,
        '../use-resource': { useResource: path => {
            if (path?.includes('/messages?')) return messages;
            if (path?.includes('/conversations?')) return resource({ content: [{ id: 'c', title: 'Existing discussion' }] });
            return resource({ id: 'r', latestIndexedCommitSha: 'a'.repeat(40) });
        } },
    });
    let tree;
    const rerender = () => { cursor = 0; tree = Chat({ repo: { id: 'r', fullName: 'acme/service' } }); return tree; };
    const find = predicate => {
        const match = descendants(tree).find(predicate);
        assert.ok(match, 'Expected element exists');
        return match;
    };
    rerender();
    find(node => node.type === 'button' && node.props.children === 'Existing discussion').props.onClick();
    rerender();
    function fill(content = 'How does authentication work?') {
        find(node => node.type === Textarea).props.onChange({ target: { value: content } });
        rerender();
    }
    const submit = () => find(node => node.type === 'form').props.onSubmit({ preventDefault() {} });
    return {
        work, calls, errors, messages, fill, submit, find,
        render: rerender,
        html: () => renderToStaticMarkup(rerender()),
        enter({ shiftKey = false, composing = false } = {}) {
            let submission, prevented = false;
            find(node => node.type === Textarea).props.onKeyDown({
                key: 'Enter', shiftKey, nativeEvent: { isComposing: composing },
                preventDefault() { prevented = true; },
                currentTarget: { form: { requestSubmit() { submission = submit(); } } },
            });
            return { submission, prevented };
        },
    };
}

const turns = () => [
    { id: 'user', role: 'user', content: 'How does authentication work?', createdAt: '2026-10-07T10:00:00Z' },
    { id: 'assistant', role: 'assistant', content: 'Authentication answer', createdAt: '2026-10-07T10:00:00.001Z',
        sources: [{ sourcePath: 'JwtService.java', chunkIndex: 0, commitSha: 'a'.repeat(40) }] },
];

test('chat shows the submitted question and accessible loader immediately and guards rapid duplicates', async () => {
    const screen = chatHarness();
    screen.fill();
    const first = screen.submit();
    const duplicate = screen.submit(); // Before a rerender: tests the synchronous guard.
    let html = screen.html();
    assert.match(html, /DevPilot is thinking…/);
    assert.match(html, /role="status" aria-live="polite" aria-atomic="true"/);
    assert.match(html, /<p>How does authentication work\?<\/p>/);
    assert.doesNotMatch(html, /What would you like to understand/);
    assert.match(html, /aria-label="Send message"[^>]*disabled=""/);
    assert.match(html, /<textarea[^>]*readOnly=""/);
    assert.equal(screen.calls.length, 1);
    assert.deepEqual(screen.calls[0], ['r', 'c', 'How does authentication work?']);
    screen.work.resolve(turns());
    await Promise.all([first, duplicate]);
    html = screen.html();
    assert.doesNotMatch(html, /DevPilot is thinking/);
    assert.match(html, /Authentication answer/);
    assert.match(html, /JwtService.java/);
    assert.match(html, /Repository sources/);
    assert.match(html, /aria-label="Send message"[^>]*disabled=""/); // Empty draft still disables Send.
    assert.match(html, /<textarea[^>]*><\/textarea>/);
});

test('failed chat clears the loader, keeps the draft, and uses the existing error toast', async () => {
    const screen = chatHarness();
    screen.fill('Please explain the service');
    const sending = screen.submit();
    assert.match(screen.html(), /DevPilot is thinking/);
    screen.work.reject(new Error('Backend unavailable'));
    await sending;
    const html = screen.html();
    assert.doesNotMatch(html, /DevPilot is thinking/);
    assert.match(html, /Please explain the service<\/textarea>/);
    assert.doesNotMatch(html, /<textarea[^>]*readOnly/);
    assert.doesNotMatch(html, /aria-label="Send message"[^>]*disabled=""/);
    assert.deepEqual(screen.errors, ['Backend unavailable']);
});

test('Enter and Send share pending, duplicate protection, and successful completion', async () => {
    for (const method of ['enter', 'send']) {
        const screen = chatHarness();
        screen.fill();
        const sending = method === 'enter' ? screen.enter().submission : screen.submit();
        assert.match(screen.html(), /DevPilot is thinking/);
        assert.equal(screen.find(node => node.props['aria-label'] === 'Send message').props.type, 'submit');
        await screen.enter().submission;
        await screen.submit();
        assert.equal(screen.calls.length, 1);
        screen.work.resolve(turns());
        await sending;
        assert.doesNotMatch(screen.html(), /DevPilot is thinking/);
        assert.match(screen.html(), /Authentication answer/);
    }
});

test('Shift+Enter and IME composition preserve textarea input instead of sending', () => {
    const screen = chatHarness();
    screen.fill();
    assert.equal(screen.enter({ shiftKey: true }).prevented, false);
    assert.equal(screen.enter({ composing: true }).prevented, false);
    assert.equal(screen.calls.length, 0);
    assert.doesNotMatch(screen.html(), /DevPilot is thinking/);
});

test('history refresh reconciles immediate returned turns without duplicate answers or sources', async () => {
    const screen = chatHarness();
    screen.fill();
    const sending = screen.submit();
    screen.work.resolve(turns());
    await sending;
    screen.messages.data = { content: [...turns()].reverse(), totalElements: 2, totalPages: 1 };
    const html = screen.html();
    assert.equal((html.match(/Authentication answer/g) ?? []).length, 1);
    assert.equal((html.match(/JwtService.java/g) ?? []).length, 1);
    assert.equal((html.match(/<p>How does authentication work\?<\/p>/g) ?? []).length, 1);
});

test('chat history controls are disabled while sending and restored after failure', async () => {
    const screen = chatHarness();
    screen.messages.data = { content: [], totalElements: 60, totalPages: 2 };
    screen.fill();
    const sending = screen.submit();
    const html = screen.html();
    assert.match(html, /<button[^>]*disabled=""[^>]*>Existing discussion<\/button>/);
    assert.match(html, /<fieldset[^>]*disabled=""/);
    screen.work.reject(new Error('Try again'));
    await sending;
    assert.doesNotMatch(screen.html(), /<fieldset[^>]*disabled=""/);
});

test('background chat answers still render normally without sources', async () => {
    const screen = chatHarness();
    screen.fill();
    const sending = screen.submit();
    const response = turns();
    delete response[1].sources;
    screen.work.resolve(response);
    await sending;
    const html = screen.html();
    assert.match(html, /Authentication answer/);
    assert.doesNotMatch(html, /Repository sources/);
    assert.doesNotMatch(html, /DevPilot is thinking/);
});

test('empty drafts do not start requests and a failed send can be retried', async () => {
    const screen = chatHarness();
    screen.fill('   ');
    await screen.submit();
    assert.equal(screen.calls.length, 0);
    assert.doesNotMatch(screen.html(), /DevPilot is thinking/);
    screen.fill('Retry this question');
    const first = screen.submit();
    screen.work.reject(new Error('Temporarily unavailable'));
    await first;
    screen.render();
    await screen.submit();
    assert.equal(screen.calls.length, 2);
    assert.doesNotMatch(screen.html(), /DevPilot is thinking/);
});
