"use client";
import { ChatMessage, ChatPendingReply } from "../chat-message";
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { api,sendChatMessage,repoApi as path,type Conversation,type Message,type Page,type Repo } from '@/lib/devpilot-api';
import { ArrowRight,BrainCircuit,Plus,Send,Sparkles } from 'lucide-react';
import { useEffect,useRef,useState,type FormEvent } from 'react';
import { Badge,EmptyState,Pager } from '../ui';
import { useResource } from '../use-resource';
import { State,useAction } from "./shared";

export function Chat({ repo }: { repo: Repo }) {
    const [conversation, setConversation] = useState('');
    const [question, setQuestion] = useState('');
    const [messagePage, setMessagePage] = useState(0);
    const [pendingMessage, setPendingMessage] = useState<Message | null>(null);
    const [recentTurns, setRecentTurns] = useState<Message[]>([]);
    const submitting = useRef(false);
    const viewport = useRef<HTMLDivElement>(null);
    const currentRepository = useResource<Repo>(path(repo.id, ''));
    const indexedCommit = currentRepository.data?.id === repo.id ? currentRepository.data.latestIndexedCommitSha : null;
    const conversations = useResource<Page<Conversation>>(path(repo.id, '/conversations?size=100'));
    const messages = useResource<Page<Message>>(conversation ? path(repo.id, '/conversations/' + conversation + '/messages?page=' + messagePage) : null);
    const action = useAction();
    const sending = pendingMessage !== null;
    const history = [...(messages.data?.content ?? [])].reverse();
    const storedIds = new Set(history.map(message => message.id));
    const newestStored = history.at(-1)?.createdAt;
    // Bridge the history refresh with the actual returned turns, preserving their sources.
    const localTurns = messagePage === 0 ? recentTurns.filter(message =>
        !storedIds.has(message.id) && (!newestStored || Date.parse(message.createdAt) >= Date.parse(newestStored))) : [];

    useEffect(() => {
        if (sending || (messagePage === 0 && recentTurns.length > 0)) {
            const element = viewport.current;
            if (element) element.scrollTop = element.scrollHeight;
        }
    }, [sending, recentTurns, messages.data, messagePage]);

    async function create() {
        if (submitting.current || action.busy) return;
        await action.run(async () => {
            const c = await api<Conversation>(path(repo.id, '/conversations'), 'POST', {
                title: 'Repository discussion · ' + new Date().toLocaleDateString(),
            });
            setConversation(c.id);
            setRecentTurns([]);
            setMessagePage(0);
            conversations.reload();
        });
    }

    async function send(e: FormEvent) {
        e.preventDefault();
        if (submitting.current || action.busy || !conversation || !question.trim()) return;
        submitting.current = true;
        const content = question;
        setMessagePage(0);
        setPendingMessage({ id: 'pending-user', role: 'user', content, createdAt: new Date().toISOString() });
        try {
            await action.run(async () => {
                const turns = await sendChatMessage(repo.id, conversation, content);
                setRecentTurns(previous => [...previous.filter(message => !storedIds.has(message.id)), ...turns].slice(-30));
                currentRepository.reload();
                setQuestion('');
                setMessagePage(0);
                messages.reload();
            });
        } finally {
            setPendingMessage(null);
            submitting.current = false;
        }
    }

    return <div className="chat-layout">
        <aside className="chat-history">
            <Button disabled={action.busy || sending} onClick={create}><Plus size={15}/>New conversation</Button>
            <h2>CONVERSATIONS</h2>
            <State resource={conversations}>{conversations.data?.content.map(c =>
                <button key={c.id} disabled={sending || action.busy} className={conversation === c.id ? 'active' : ''}
                    onClick={() => { setConversation(c.id); setRecentTurns([]); setMessagePage(0); }}>
                    {c.title}
                </button>)}
            </State>
        </aside>
        <section className="chat-panel">
            <header><BrainCircuit size={18}/><strong>{repo.fullName}</strong>
                <Badge>{indexedCommit ? "INDEXED REPOSITORY" : "REPOSITORY BACKGROUND"}</Badge>
            </header>
            {conversation ? <>
                <div className="messages" ref={viewport}>
                    <State resource={messages}>
                        {history.map(message => <ChatMessage key={message.id} message={message}/>)}
                        {messages.data?.totalElements === 0 && localTurns.length === 0 && !sending &&
                            <EmptyState title="What would you like to understand?" detail="Ask about recent findings, recurring issues, or the latest analyzed change."/>}
                    </State>
                    {localTurns.map(message => <ChatMessage key={message.id} message={message}/>)}
                    {pendingMessage && <ChatMessage message={pendingMessage}/>}
                    {sending && <ChatPendingReply/>}
                    {(messages.data?.totalPages ?? 0) > 1 &&
                        <fieldset className="chat-pagination" disabled={sending}>
                            <Pager page={messagePage} total={messages.data?.totalPages ?? 0} onChange={setMessagePage}/>
                        </fieldset>}
                </div>
                <form className="chat-compose" onSubmit={send} aria-busy={sending}>
                    <Textarea aria-label="Message" value={question} readOnly={sending} aria-disabled={sending}
                        onChange={e => setQuestion(e.target.value)}
                        onKeyDown={e => {
                            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                                e.preventDefault();
                                e.currentTarget.form?.requestSubmit();
                            }
                        }}
                        placeholder="What technical debt should I investigate first?" maxLength={4000} required/>
                    <Button type="submit" aria-label="Send message" disabled={action.busy || sending || !question.trim()}>
                        <Send size={18}/>
                    </Button>
                </form>
                <small className="chat-note">
                    {currentRepository.loading ? "Checking repository knowledge. " : currentRepository.error ? "Repository index status could not be loaded. " : indexedCommit ? "Uses indexed repository source at " + indexedCommit.slice(0, 7) + ". " : "Repository knowledge not indexed yet. Uses stored repository background. "}
                    Responses can be mistaken; verify the evidence.
                </small>
            </> : <div className="chat-empty">
                <Sparkles size={38}/><h2>Ask better questions of your code.</h2>
                <p>Start a conversation to explore the context behind your repository.</p>
                <Button disabled={action.busy} onClick={create}>Start a conversation<ArrowRight size={15}/></Button>
            </div>}
        </section>
    </div>;
}
