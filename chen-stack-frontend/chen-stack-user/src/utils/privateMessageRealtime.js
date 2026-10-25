import { watch } from 'vue'
import { useUserStore } from '@/stores/userStore'
import { useMessageStore } from '@/stores/messageStore'
import { getConversationList } from '@/api/conversation'
import { GetJwt } from '@/utils/Auth'
import { sameJwtAccount } from '@/utils/JwtSession'
import WebSocketClient from '@/utils/WebSocketClient'

// App 拥有唯一连接，页面切换不移除私信订阅；账号切换先清理旧状态。
export function usePrivateMessageRealtime() {
  const userStore = useUserStore()
  const store = useMessageStore()
  const stop = watch(
    () => userStore.user?.id,
    (ownerId, previousId, onCleanup) => {
      WebSocketClient.close()
      store.clearAll()
      if (!ownerId || !GetJwt()) return
      const token = GetJwt()
      let active = true
      let refreshSequence = 0
      const current = () =>
        active && userStore.user?.id === ownerId && sameJwtAccount(token, GetJwt())
      const refresh = async () => {
        const sequence = ++refreshSequence
        const version = store.getConversationVersion()
        try {
          const res = await getConversationList()
          if (current() && sequence === refreshSequence) {
            store.setConversationList(res.data || [], version)
          }
        } catch {
          // 保留已有状态，下次连接恢复或用户刷新时重取。
        }
      }
      const receive = (data) => {
        if (!current()) return
        if (store.receiveMessage(data, ownerId) && store.currentChatUserId === data.fromUserId) {
          WebSocketClient.markAsRead(data.fromUserId)
        }
      }
      const acknowledge = (data) => {
        if (!current()) return
        store.acknowledgeMessage({
          ...data,
          fromUserNickname: userStore.user?.nickname,
          fromUserAvatar: userStore.user?.avatar,
        }, ownerId)
      }
      const revoke = (data) => {
        if (!current()) return
        const known = store.conversationList.some((conv) => conv.targetUserId === data.fromUserId)
        if (!known) return
        store.updateConversationLastMessage(data.fromUserId, data.content || '撤回了一条消息')
        refresh()
      }
      WebSocketClient.on('NEW_MESSAGE', receive)
      WebSocketClient.on('SEND_SUCCESS', acknowledge)
      WebSocketClient.on('MESSAGE_REVOKED', revoke)
      WebSocketClient.on('open', refresh)
      onCleanup(() => {
        active = false
        WebSocketClient.off('NEW_MESSAGE', receive)
        WebSocketClient.off('SEND_SUCCESS', acknowledge)
        WebSocketClient.off('MESSAGE_REVOKED', revoke)
        WebSocketClient.off('open', refresh)
        WebSocketClient.close()
        store.clearAll()
      })
      WebSocketClient.connect()
    },
    { immediate: true, flush: 'sync' },
  )
  return stop
}
