export function buildInviteCodeShareMessage(shareLink: string, inviteCode: string) {
  return [
    'IntelliDoc 邀请码',
    `邀请码：${inviteCode}`,
    `注册链接：${shareLink}`,
    '',
    '如邀请码失效，请联系系统管理员。'
  ].join('\n');
}
