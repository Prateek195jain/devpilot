import type { Message } from "../../lib/devpilot-api";

export function ChatMessage({ message }: { message: Message }) {
    const paths = message.role === "assistant"
        ? [...new Set((message.sources ?? []).map(source => source.sourcePath))]
        : [];
    return <div className={"message " + message.role}>
        <div>{message.role === "user" ? "You" : "DevPilot"}</div>
        <p>{message.content}</p>
        {paths.length > 0 && <section className="message-sources" aria-label="Repository sources">
            <span>Sources</span>
            <ul>{paths.map(path => <li key={path}><code>{path}</code></li>)}</ul>
        </section>}
    </div>;
}

export function ChatPendingReply() {
    return <div className="message assistant message-pending" role="status" aria-live="polite" aria-atomic="true">
        <div>DevPilot</div>
        <p><span className="thinking-dots" aria-hidden="true"><span/><span/><span/></span>DevPilot is thinking…</p>
    </div>;
}
