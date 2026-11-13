import {
  ApiProblemError,
  type ApiClient,
  type CollabChannel,
  type CollabChannelDetail,
  type CollabFileLink,
  type CollabInbox,
  type CollabMeeting,
  type CollabPage,
  type CollabPost,
  type CollabPostDetail,
  type CollabTask,
} from '@platform/api-client';
import {
  Alert,
  Badge,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  List,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { navigate } from '../router';
import { describeError, formatDateTime } from '../utils/format';

const { Text, Paragraph } = Typography;

export interface CollaborationViewProps {
  apiClient: ApiClient;
  channelId?: string;
}

interface ChannelFormValues {
  orgUnitId: string;
  name: string;
  scope: 'all' | 'members' | 'cross_department';
  topic?: string;
}

interface PostFormValues {
  title: string;
  body?: string;
}

interface TaskFormValues {
  orgUnitId: string;
  projectId?: string;
  title: string;
  description?: string;
  dueDate?: string;
}

interface MeetingFormValues {
  orgUnitId: string;
  title: string;
  meetingDate: string;
  notes?: string;
}

interface PageFormValues {
  orgUnitId: string;
  title: string;
  body?: string;
}

/** 部门协作：频道列表与频道详情共用同一视图，保证深链可刷新。 */
export function CollaborationView({ apiClient, channelId }: CollaborationViewProps) {
  const [channels, setChannels] = useState<CollabChannel[]>([]);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [inbox, setInbox] = useState<CollabInbox | null>(null);
  const [inboxOpen, setInboxOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchHits, setSearchHits] = useState<
    Array<{ kind: string; id: string; title: string; snippet: string }>
  >([]);

  const [channelForm] = Form.useForm<ChannelFormValues>();
  const [postForm] = Form.useForm<PostFormValues>();
  const [taskForm] = Form.useForm<TaskFormValues>();
  const [meetingForm] = Form.useForm<MeetingFormValues>();
  const [pageForm] = Form.useForm<PageFormValues>();
  const [channelModalOpen, setChannelModalOpen] = useState(false);

  const [detail, setDetail] = useState<CollabChannelDetail | null>(null);
  const [detailPhase, setDetailPhase] = useState<AsyncPhase>('loading');
  const [detailError, setDetailError] = useState<string | null>(null);
  const [files, setFiles] = useState<CollabFileLink[]>([]);
  const [tasks, setTasks] = useState<CollabTask[]>([]);
  const [meetings, setMeetings] = useState<CollabMeeting[]>([]);
  const [pages, setPages] = useState<CollabPage[]>([]);
  const [selectedPost, setSelectedPost] = useState<CollabPostDetail | null>(null);
  const [commentBody, setCommentBody] = useState('');
  const [fileId, setFileId] = useState('');
  const [memberId, setMemberId] = useState('');

  const loadChannels = useCallback(() => {
    setPhase('loading');
    setForbidden(false);
    apiClient
      .listCollabChannels(100)
      .then((rows) => {
        setChannels(rows);
        setPhase(rows.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiProblemError && cause.status === 403) {
          setForbidden(true);
        }
        setError(describeError(cause));
        setPhase('error');
      });
  }, [apiClient]);

  const loadDetail = useCallback(() => {
    if (!channelId) {
      return;
    }
    setDetailPhase('loading');
    apiClient
      .getCollabChannel(channelId)
      .then(async (value) => {
        setDetail(value);
        const results = await Promise.allSettled([
          apiClient.listCollabFileLinks({ channelId }),
          apiClient.listCollabTasks(),
          apiClient.listCollabMeetings(value.channel.orgUnitId),
          apiClient.listCollabPages(value.channel.orgUnitId),
        ]);
        const [fileRows, taskRows, meetingRows, pageRows] = results;
        if (fileRows.status === 'fulfilled') {
          setFiles(fileRows.value);
        }
        if (taskRows.status === 'fulfilled') {
          setTasks(taskRows.value);
        }
        if (meetingRows.status === 'fulfilled') {
          setMeetings(meetingRows.value);
        }
        if (pageRows.status === 'fulfilled') {
          setPages(pageRows.value);
        }
        const failed = results.some((result) => result.status === 'rejected');
        setDetailPhase(failed ? 'partial' : 'ready');
      })
      .catch((cause: unknown) => {
        setDetailError(describeError(cause));
        setDetailPhase('error');
      });
  }, [apiClient, channelId]);

  useEffect(loadChannels, [loadChannels]);
  useEffect(loadDetail, [loadDetail]);

  useEffect(() => {
    let active = true;
    apiClient
      .listNotifications(20)
      .then((value) => {
        if (active) {
          setInbox(value);
        }
      })
      .catch(() => {
        if (active) {
          setInbox(null);
        }
      });
    return () => {
      active = false;
    };
  }, [apiClient]);

  async function createChannel(values: ChannelFormValues): Promise<void> {
    try {
      const created = await apiClient.createCollabChannel({
        orgUnitId: values.orgUnitId,
        scope: values.scope,
        name: values.name,
        topic: values.topic ?? '',
      });
      message.success('频道已创建');
      setChannelModalOpen(false);
      channelForm.resetFields();
      navigate('collaborationChannel', { channelId: created.id });
      loadChannels();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function createPost(values: PostFormValues): Promise<void> {
    if (!channelId) {
      return;
    }
    try {
      await apiClient.createCollabPost(channelId, {
        title: values.title,
        body: values.body ?? '',
        mentionPersonIds: [],
      });
      message.success('帖子已发布');
      postForm.resetFields();
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function openPost(postId: string): Promise<void> {
    try {
      setSelectedPost(await apiClient.getCollabPost(postId));
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function addComment(): Promise<void> {
    if (!selectedPost) {
      return;
    }
    try {
      await apiClient.addCollabComment(selectedPost.post.id, {
        body: commentBody,
        mentionPersonIds: [],
      });
      setCommentBody('');
      setSelectedPost(await apiClient.getCollabPost(selectedPost.post.id));
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function createTask(values: TaskFormValues): Promise<void> {
    try {
      await apiClient.createCollabTask({
        orgUnitId: values.orgUnitId,
        projectId: values.projectId ? values.projectId : null,
        milestoneId: null,
        title: values.title,
        description: values.description ?? '',
        assigneePersonId: null,
        dueDate: values.dueDate ? values.dueDate : null,
      });
      message.success('任务已创建');
      taskForm.resetFields();
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function createMeeting(values: MeetingFormValues): Promise<void> {
    try {
      await apiClient.createCollabMeeting({
        orgUnitId: values.orgUnitId,
        projectId: null,
        title: values.title,
        meetingDate: values.meetingDate,
        notes: values.notes ?? '',
      });
      message.success('会议已创建');
      meetingForm.resetFields();
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function createPage(values: PageFormValues): Promise<void> {
    try {
      await apiClient.createCollabPage({
        orgUnitId: values.orgUnitId,
        parentId: null,
        title: values.title,
        body: values.body ?? '',
      });
      message.success('知识页已创建');
      pageForm.resetFields();
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function linkFile(): Promise<void> {
    if (!channelId || !fileId.trim()) {
      return;
    }
    try {
      await apiClient.linkCollabFile({ channelId, fileId: fileId.trim() });
      setFileId('');
      message.success('已引用文件');
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function addMember(): Promise<void> {
    if (!channelId || !memberId.trim()) {
      return;
    }
    try {
      await apiClient.addChannelMember(channelId, { personId: memberId.trim(), role: 'member' });
      setMemberId('');
      message.success('成员已加入');
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function removeMember(personId: string): Promise<void> {
    if (!channelId) {
      return;
    }
    try {
      const result = await apiClient.removeChannelMember(channelId, personId);
      message.success(
        `已移除成员，撤销 ${result.revokedShareLinks} 条分享链接、清理 ${result.dismissedNotifications} 条未读通知`,
      );
      loadDetail();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  async function archive(): Promise<void> {
    if (!channelId) {
      return;
    }
    try {
      await apiClient.archiveChannel(channelId);
      message.success('频道已归档');
      navigate('collaboration');
      loadChannels();
    } catch (cause) {
      message.error(describeError(cause));
    }
  }

  function runSearch(): void {
    apiClient
      .searchCollaboration(searchQuery)
      .then(setSearchHits)
      .catch((cause) => message.error(describeError(cause)));
  }

  if (forbidden) {
    return <Alert type="warning" showIcon message="无权访问部门协作" />;
  }

  if (!channelId) {
    return (
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card
          title="协作搜索"
          extra={
            <Badge count={inbox?.unread ?? 0} size="small">
              <Button onClick={() => setInboxOpen(true)}>通知</Button>
            </Badge>
          }
        >
          <Space.Compact style={{ width: '100%' }}>
            <Input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="搜索帖子、知识页与任务（至少 2 个字符）"
              onPressEnter={runSearch}
            />
            <Button type="primary" onClick={runSearch}>
              搜索
            </Button>
          </Space.Compact>
          {searchHits.length > 0 && (
            <List
              size="small"
              style={{ marginTop: 12 }}
              dataSource={searchHits}
              renderItem={(hit) => (
                <List.Item>
                  <Space direction="vertical" size={0}>
                    <Space>
                      <Tag>{hit.kind}</Tag>
                      <Text strong>{hit.title}</Text>
                    </Space>
                    <Text type="secondary">{hit.snippet}</Text>
                  </Space>
                </List.Item>
              )}
            />
          )}
        </Card>

        <Card
          title="频道"
          extra={<Button onClick={() => setChannelModalOpen(true)}>新建频道</Button>}
        >
          <AsyncState
            phase={phase}
            {...(error ? { errorMessage: error } : {})}
            emptyText="还没有可见频道"
            onRetry={loadChannels}
          >
            <Table<CollabChannel>
              rowKey="id"
              size="small"
              pagination={false}
              dataSource={channels}
              columns={[
                {
                  title: '名称',
                  dataIndex: 'name',
                  render: (name: string, row) => (
                    <a href={`#/collaboration/${row.id}`}>{name}</a>
                  ),
                },
                { title: '范围', dataIndex: 'scope' },
                { title: '主题', dataIndex: 'topic' },
                {
                  title: '创建时间',
                  dataIndex: 'createdAt',
                  render: (value: string) => formatDateTime(value),
                },
              ]}
            />
          </AsyncState>
        </Card>

        <Modal
          title="新建频道"
          open={channelModalOpen}
          onCancel={() => setChannelModalOpen(false)}
          onOk={() => channelForm.submit()}
          destroyOnHidden
        >
          <Form<ChannelFormValues>
            form={channelForm}
            layout="vertical"
            onFinish={createChannel}
            initialValues={{ scope: 'members' }}
          >
            <Form.Item name="orgUnitId" label="部门 ID" rules={[{ required: true }]}>
              <Input placeholder="组织单元 UUID" />
            </Form.Item>
            <Form.Item name="name" label="频道名称" rules={[{ required: true }]}>
              <Input maxLength={120} />
            </Form.Item>
            <Form.Item name="scope" label="可见范围" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'members', label: '仅成员' },
                  { value: 'all', label: '部门全员' },
                  { value: 'cross_department', label: '跨部门' },
                ]}
              />
            </Form.Item>
            <Form.Item name="topic" label="主题">
              <Input.TextArea rows={2} maxLength={1000} />
            </Form.Item>
          </Form>
        </Modal>

        <Modal
          title="我的通知"
          open={inboxOpen}
          onCancel={() => setInboxOpen(false)}
          footer={null}
          width={560}
        >
          <AsyncState
            phase={inbox ? (inbox.rows.length === 0 ? 'empty' : 'ready') : 'loading'}
            emptyText="暂无通知"
          >
            <List
              size="small"
              dataSource={inbox?.rows ?? []}
              renderItem={(row) => (
                <List.Item
                  actions={[
                    !row.readAt ? (
                      <Button
                        key="read"
                        size="small"
                        onClick={() => {
                          apiClient
                            .markNotificationRead(row.id)
                            .then(() => apiClient.listNotifications(20))
                            .then(setInbox)
                            .catch((cause) => message.error(describeError(cause)));
                        }}
                      >
                        标记已读
                      </Button>
                    ) : (
                      <Tag key="read">已读</Tag>
                    ),
                  ]}
                >
                  <Space direction="vertical" size={0}>
                    <Text strong={!row.readAt}>{row.title}</Text>
                    <Text type="secondary">{row.body}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {row.resourceType} · {formatDateTime(row.createdAt)}
                    </Text>
                  </Space>
                </List.Item>
              )}
            />
          </AsyncState>
        </Modal>
      </Space>
    );
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={detail ? `频道：${detail.channel.name}` : '频道'}
        extra={
          <Space>
            <Button onClick={() => navigate('collaboration')}>返回频道列表</Button>
            <Button danger onClick={archive}>
              归档
            </Button>
          </Space>
        }
      >
        <AsyncState
          phase={detailPhase}
          {...(detailError ? { errorMessage: detailError } : {})}
          partialText="部分分区加载失败，可切换标签或重试。"
          onRetry={loadDetail}
        >
          {detail && (
            <Descriptions size="small" column={3}>
              <Descriptions.Item label="范围">{detail.channel.scope}</Descriptions.Item>
              <Descriptions.Item label="主题">{detail.channel.topic || '—'}</Descriptions.Item>
              <Descriptions.Item label="成员">{detail.members.length}</Descriptions.Item>
            </Descriptions>
          )}
        </AsyncState>
      </Card>

      <Card>
        <Tabs
          items={[
            {
              key: 'posts',
              label: '帖子',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Form<PostFormValues>
                    form={postForm}
                    layout="inline"
                    onFinish={createPost}
                    style={{ rowGap: 8 }}
                  >
                    <Form.Item name="title" rules={[{ required: true }]}>
                      <Input placeholder="帖子标题" style={{ width: 260 }} maxLength={200} />
                    </Form.Item>
                    <Form.Item name="body">
                      <Input placeholder="正文" style={{ width: 360 }} maxLength={20000} />
                    </Form.Item>
                    <Button htmlType="submit" type="primary">
                      发布
                    </Button>
                  </Form>
                  <List
                    size="small"
                    dataSource={detail?.posts ?? []}
                    locale={{ emptyText: '暂无帖子' }}
                    renderItem={(post: CollabPost) => (
                      <List.Item
                        actions={[
                          <Button key="open" size="small" onClick={() => openPost(post.id)}>
                            查看
                          </Button>,
                          <Button
                            key="ack"
                            size="small"
                            onClick={() => {
                              apiClient
                                .acknowledgeCollabPost(post.id)
                                .then(() => message.success('已确认'))
                                .catch((cause) => message.error(describeError(cause)));
                            }}
                          >
                            确认收到
                          </Button>,
                          <Button
                            key="pin"
                            size="small"
                            onClick={() => {
                              apiClient
                                .pinCollabPost(post.id)
                                .then(loadDetail)
                                .catch((cause) => message.error(describeError(cause)));
                            }}
                          >
                            置顶
                          </Button>,
                        ]}
                      >
                        <List.Item.Meta
                          title={
                            <Space>
                              {post.pinnedAt ? <Tag color="blue">置顶</Tag> : null}
                              <Text strong>{post.title}</Text>
                            </Space>
                          }
                          description={`${post.body} · ${formatDateTime(post.createdAt)}`}
                        />
                      </List.Item>
                    )}
                  />
                </Space>
              ),
            },
            {
              key: 'tasks',
              label: '任务',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Form<TaskFormValues>
                    form={taskForm}
                    layout="inline"
                    onFinish={createTask}
                    initialValues={detail ? { orgUnitId: detail.channel.orgUnitId } : {}}
                    style={{ rowGap: 8 }}
                  >
                    <Form.Item name="orgUnitId" rules={[{ required: true }]}>
                      <Input placeholder="部门 ID" style={{ width: 280 }} />
                    </Form.Item>
                    <Form.Item name="title" rules={[{ required: true }]}>
                      <Input placeholder="任务标题" style={{ width: 240 }} maxLength={200} />
                    </Form.Item>
                    <Form.Item name="dueDate">
                      <Input type="date" style={{ width: 160 }} />
                    </Form.Item>
                    <Button htmlType="submit" type="primary">
                      新建任务
                    </Button>
                  </Form>
                  <Table<CollabTask>
                    rowKey="id"
                    size="small"
                    pagination={false}
                    dataSource={tasks.slice(0, 50)}
                    columns={[
                      { title: '任务', dataIndex: 'title' },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        render: (status: string, row) => (
                          <Select
                            size="small"
                            value={status}
                            style={{ width: 110 }}
                            options={[
                              { value: 'todo', label: '待办' },
                              { value: 'doing', label: '进行中' },
                              { value: 'done', label: '已完成' },
                            ]}
                            onChange={(next) => {
                              apiClient
                                .updateCollabTaskStatus(row.id,
                                  next as 'todo' | 'doing' | 'done')
                                .then(loadDetail)
                                .catch((cause) => message.error(describeError(cause)));
                            }}
                          />
                        ),
                      },
                      {
                        title: '截止',
                        dataIndex: 'dueDate',
                        render: (value?: string | null) => value ?? '—',
                      },
                      {
                        title: '负责人',
                        dataIndex: 'assigneePersonId',
                        render: (value?: string | null) => value ?? '—',
                      },
                    ]}
                  />
                </Space>
              ),
            },
            {
              key: 'meetings',
              label: '会议',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Form<MeetingFormValues>
                    form={meetingForm}
                    layout="inline"
                    onFinish={createMeeting}
                    initialValues={detail ? { orgUnitId: detail.channel.orgUnitId } : {}}
                    style={{ rowGap: 8 }}
                  >
                    <Form.Item name="orgUnitId" rules={[{ required: true }]}>
                      <Input placeholder="部门 ID" style={{ width: 280 }} />
                    </Form.Item>
                    <Form.Item name="title" rules={[{ required: true }]}>
                      <Input placeholder="会议标题" style={{ width: 220 }} maxLength={200} />
                    </Form.Item>
                    <Form.Item name="meetingDate" rules={[{ required: true }]}>
                      <Input type="date" style={{ width: 160 }} />
                    </Form.Item>
                    <Button htmlType="submit" type="primary">
                      记录会议
                    </Button>
                  </Form>
                  <List
                    size="small"
                    dataSource={meetings}
                    locale={{ emptyText: '暂无会议' }}
                    renderItem={(meeting) => (
                      <List.Item>
                        <List.Item.Meta
                          title={meeting.title}
                          description={`${meeting.meetingDate} · ${meeting.notes || '无纪要'}`}
                        />
                      </List.Item>
                    )}
                  />
                </Space>
              ),
            },
            {
              key: 'pages',
              label: '知识页',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Form<PageFormValues>
                    form={pageForm}
                    layout="inline"
                    onFinish={createPage}
                    initialValues={detail ? { orgUnitId: detail.channel.orgUnitId } : {}}
                    style={{ rowGap: 8 }}
                  >
                    <Form.Item name="orgUnitId" rules={[{ required: true }]}>
                      <Input placeholder="部门 ID" style={{ width: 280 }} />
                    </Form.Item>
                    <Form.Item name="title" rules={[{ required: true }]}>
                      <Input placeholder="页面标题" style={{ width: 240 }} maxLength={200} />
                    </Form.Item>
                    <Button htmlType="submit" type="primary">
                      新建页面
                    </Button>
                  </Form>
                  <List
                    size="small"
                    dataSource={pages}
                    locale={{ emptyText: '暂无知识页' }}
                    renderItem={(page) => (
                      <List.Item>
                        <List.Item.Meta
                          title={page.title}
                          description={`${page.body.slice(0, 120)} · ${formatDateTime(page.updatedAt)}`}
                        />
                      </List.Item>
                    )}
                  />
                </Space>
              ),
            },
            {
              key: 'files',
              label: '文件',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      value={fileId}
                      onChange={(event) => setFileId(event.target.value)}
                      placeholder="引用已有文件 ID（下载仍按文件 ACL 鉴权）"
                    />
                    <Button type="primary" onClick={linkFile}>
                      引用
                    </Button>
                  </Space.Compact>
                  <Table<CollabFileLink>
                    rowKey="id"
                    size="small"
                    pagination={false}
                    dataSource={files}
                    locale={{ emptyText: '暂无被授权的文件引用' }}
                    columns={[
                      { title: '文件名', dataIndex: 'fileName' },
                      { title: '类型', dataIndex: 'contentType' },
                      {
                        title: '大小',
                        dataIndex: 'sizeBytes',
                        render: (value: number) => `${Math.round(value / 1024)} KB`,
                      },
                      {
                        title: '引用时间',
                        dataIndex: 'createdAt',
                        render: (value: string) => formatDateTime(value),
                      },
                    ]}
                  />
                </Space>
              ),
            },
            {
              key: 'members',
              label: '成员',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      value={memberId}
                      onChange={(event) => setMemberId(event.target.value)}
                      placeholder="员工 UUID"
                    />
                    <Button type="primary" onClick={addMember}>
                      添加成员
                    </Button>
                  </Space.Compact>
                  <Table
                    rowKey="personId"
                    size="small"
                    pagination={false}
                    dataSource={detail?.members ?? []}
                    columns={[
                      { title: '员工', dataIndex: 'personId' },
                      {
                        title: '角色',
                        dataIndex: 'role',
                        render: (role: string) => <Tag>{role}</Tag>,
                      },
                      {
                        title: '状态',
                        dataIndex: 'removedAt',
                        render: (removedAt?: string | null) =>
                          removedAt ? <Tag color="red">已移除</Tag> : <Tag color="green">在组</Tag>,
                      },
                      {
                        title: '操作',
                        render: (_value: unknown, row) =>
                          row.removedAt ? null : (
                            <Button
                              size="small"
                              danger
                              onClick={() => removeMember(row.personId)}
                            >
                              移除
                            </Button>
                          ),
                      },
                    ]}
                  />
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={selectedPost?.post.title ?? '帖子'}
        open={selectedPost !== null}
        onCancel={() => setSelectedPost(null)}
        footer={null}
        width={680}
      >
        {selectedPost && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Paragraph>{selectedPost.post.body || '（无正文）'}</Paragraph>
            <Text type="secondary">
              {formatDateTime(selectedPost.post.createdAt)} · 已确认 {selectedPost.receipts.length} 人
            </Text>
            <List
              size="small"
              header={<Text strong>评论</Text>}
              dataSource={selectedPost.comments}
              locale={{ emptyText: '暂无评论' }}
              renderItem={(comment) => (
                <List.Item>
                  <List.Item.Meta
                    title={comment.authorPersonId}
                    description={comment.body}
                  />
                </List.Item>
              )}
            />
            <Space.Compact style={{ width: '100%' }}>
              <Input
                value={commentBody}
                onChange={(event) => setCommentBody(event.target.value)}
                placeholder="写评论"
              />
              <Button type="primary" onClick={addComment}>
                发送
              </Button>
            </Space.Compact>
          </Space>
        )}
      </Modal>
    </Space>
  );
}
