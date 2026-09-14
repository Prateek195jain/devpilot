create table users (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 email varchar(255) not null,
 name varchar(100) not null,
 password_hash varchar(255) not null,
 role varchar(20) not null
);
create table refresh_tokens (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 user_id uuid not null,
 token_hash varchar(64) not null,
 expires_at timestamptz not null,
 revoked_at timestamptz
);
create table github_integrations (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 user_id uuid not null,
 login varchar(100) not null,
 encrypted_token text not null
);
create table repositories (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 owner_id uuid not null,
 github_id bigint not null,
 full_name varchar(200) not null,
 description text,
 default_branch varchar(200) not null,
 language varchar(100),
 readme text,
 synced_at timestamptz
);
create table analyses (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 repository_id uuid not null,
 kind varchar(20) not null,
 ref varchar(200) not null,
 status varchar(20) not null,
 source varchar(40) not null,
 summary text,
 diff text,
 error varchar(500),
 attempts integer not null,
 started_at timestamptz,
 completed_at timestamptz,
 next_attempt_at timestamptz
);
create table findings (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 analysis_id uuid not null,
 repository_id uuid not null,
 type varchar(50) not null,
 severity varchar(20) not null,
 title varchar(200) not null,
 description text,
 file varchar(500) not null,
 line_number integer,
 recommendation text not null,
 confidence double precision not null,
 source varchar(40) not null,
 fingerprint varchar(64) not null
);
create table engineering_memories (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 repository_id uuid not null,
 fingerprint varchar(64) not null,
 type varchar(50) not null,
 description text,
 occurrences integer not null,
 last_seen timestamptz not null,
 status varchar(20) not null
);
create table technical_debt (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 repository_id uuid not null,
 source_finding_id uuid,
 title varchar(200) not null,
 description text,
 category varchar(50) not null,
 severity varchar(20) not null,
 status varchar(20) not null,
 estimated_effort varchar(100) not null,
 updated_at timestamptz not null,
 resolved_at timestamptz
);
create table conversations (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 repository_id uuid not null,
 title varchar(200) not null
);
create table messages (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 conversation_id uuid not null,
 role varchar(20) not null,
 content text not null
);
create table generated_documents (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 repository_id uuid not null,
 kind varchar(50) not null,
 document_version integer not null,
 content text not null,
 source varchar(40) not null
);
create table webhook_events (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 delivery_id varchar(100) not null,
 event_type varchar(50) not null,
 payload_hash varchar(64) not null
);
alter table refresh_tokens add foreign key (user_id) references users(id);
create index idx_refresh_tokens_user_id on refresh_tokens(user_id);
alter table github_integrations add foreign key (user_id) references users(id);
create index idx_github_integrations_user_id on github_integrations(user_id);
alter table repositories add foreign key (owner_id) references users(id);
create index idx_repositories_owner_id on repositories(owner_id);
alter table analyses add foreign key (repository_id) references repositories(id);
create index idx_analyses_repository_id on analyses(repository_id);
alter table findings add foreign key (analysis_id) references analyses(id);
create index idx_findings_analysis_id on findings(analysis_id);
alter table findings add foreign key (repository_id) references repositories(id);
create index idx_findings_repository_id on findings(repository_id);
alter table engineering_memories add foreign key (repository_id) references repositories(id);
create index idx_engineering_memories_repository_id on engineering_memories(repository_id);
alter table technical_debt add foreign key (repository_id) references repositories(id);
create index idx_technical_debt_repository_id on technical_debt(repository_id);
alter table technical_debt add foreign key (source_finding_id) references findings(id);
create index idx_technical_debt_source_finding_id on technical_debt(source_finding_id);
alter table conversations add foreign key (repository_id) references repositories(id);
create index idx_conversations_repository_id on conversations(repository_id);
alter table messages add foreign key (conversation_id) references conversations(id);
create index idx_messages_conversation_id on messages(conversation_id);
alter table generated_documents add foreign key (repository_id) references repositories(id);
create index idx_generated_documents_repository_id on generated_documents(repository_id);
alter table users add unique (email);
alter table refresh_tokens add unique (token_hash);
alter table github_integrations add unique (user_id);
alter table repositories add unique (owner_id,github_id);
alter table engineering_memories add unique (repository_id,fingerprint);
alter table technical_debt add unique (repository_id,source_finding_id);
alter table generated_documents add unique (repository_id,kind,document_version);
alter table webhook_events add unique (delivery_id);
create index idx_analysis_queue on analyses(status,next_attempt_at,created_at);
create index idx_messages_history on messages(conversation_id,created_at);
alter table technical_debt add check (status in ('OPEN','IN_PROGRESS','RESOLVED','IGNORED'));
alter table findings add check (confidence between 0 and 1);
alter table users add check (role in ('USER','ADMIN'));
alter table findings add check (severity in ('CRITICAL','HIGH','MEDIUM','LOW'));
alter table technical_debt add check (severity in ('CRITICAL','HIGH','MEDIUM','LOW'));

alter table conversations add column updated_at timestamptz not null default now();
