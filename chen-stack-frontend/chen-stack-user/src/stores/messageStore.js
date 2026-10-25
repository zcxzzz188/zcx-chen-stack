import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useMessageStore = defineStore(
  'message',
  () => {
    // 会话列表
    const conversationList = ref([])

    // 总未读数
    const totalUnreadCount = ref(0)

    // 当前聊天窗口的用户ID
    const currentChatUserId = ref(null)

    // 当前聊天窗口的消息列表
    const currentChatMessages = ref([])

    // 当前聊天用户是否正在输入
    const targetUserTyping = ref(false)

    // 各会话的输入状态 Map<userId, boolean>
    const conversationTypingMap = ref({})
    const receivedMessageIds = new Set()
    const conversationChanges = new Map()
    let conversationVersion = 0

    const changedConversation = (userId) => {
      conversationChanges.set(Number(userId), ++conversationVersion)
    }

    const getConversationVersion = () => conversationVersion

    /**
     * 设置会话列表
     */
    const setConversationList = (list, requestedVersion) => {
      if (requestedVersion !== undefined) {
        const changed = (id) => (conversationChanges.get(Number(id)) || 0) > requestedVersion
        list = [
          ...conversationList.value.filter((conv) => changed(conv.targetUserId)),
          ...list.filter((conv) => !changed(conv.targetUserId)),
        ]
      }
      conversationList.value = list.map((conv) => ({
        ...conv,
        unreadCount: conv.targetUserId === currentChatUserId.value ? 0 : Number(conv.unreadCount) || 0,
      }))
      // 计算总未读数
      totalUnreadCount.value = conversationList.value.reduce((sum, conv) => sum + conv.unreadCount, 0)
    }

    /**
     * 更新会话（收到新消息时）
     * @param userId - 发送消息的用户ID
     * @param message - 消息内容
     * @param unreadCount - 可选，后端返回的最新未读数
     * @param userInfo - 可选，用户信息（昵称、头像等）
     */
    const updateConversation = (userId, message, unreadCount, userInfo) => {
      changedConversation(userId)
      if (currentChatUserId.value === userId) unreadCount = 0
      const index = conversationList.value.findIndex((conv) => conv.targetUserId === userId)

      if (index !== -1) {
        // 更新已有会话
        const conv = conversationList.value[index]
        const oldUnreadCount = conv.unreadCount || 0

        // 创建新的会话对象（确保触发响应式更新）
        const updatedConv = {
          ...conv,
          lastMessageContent: message.content,
          lastMessageTime: message.createTime,
        }

        // 更新用户信息（如果提供了）
        if (userInfo) {
          if (userInfo.nickname) updatedConv.targetUserNickname = userInfo.nickname
          if (userInfo.avatar) updatedConv.targetUserAvatar = userInfo.avatar
        }

        // 如果后端传了最新的未读数，直接使用
        if (typeof unreadCount === 'number') {
          updatedConv.unreadCount = unreadCount
          // 更新总未读数
          totalUnreadCount.value = totalUnreadCount.value - oldUnreadCount + unreadCount
        } else {
          // 如果后端没传，且不是当前聊天窗口，才增加未读数
          if (currentChatUserId.value !== userId) {
            updatedConv.unreadCount = oldUnreadCount + 1
            totalUnreadCount.value++
          } else {
            updatedConv.unreadCount = oldUnreadCount
          }
        }

        // 将有新消息的会话移到列表最顶（使用 splice 确保触发响应式更新）
        // 先删除，再插入，确保列表最顶部有该会话
        conversationList.value.splice(index, 1)
        conversationList.value.unshift(updatedConv)
      } else {
        // 创建新会话
        const newUnreadCount =
          typeof unreadCount === 'number' ? unreadCount : currentChatUserId.value === userId ? 0 : 1

        const newConversation = {
          id: userId, // 添加 id 字段，确保 v-for 的 key 正确
          targetUserId: userId,
          targetUserNickname: userInfo?.nickname || '新用户',
          targetUserAvatar: userInfo?.avatar || '',
          lastMessageContent: message.content,
          lastMessageTime: message.createTime,
          unreadCount: newUnreadCount,
          isOnline: false,
        }

        conversationList.value.unshift(newConversation)
        totalUnreadCount.value += newUnreadCount
      }
    }

    /**
     * 清空某个会话的未读数
     */
    const clearConversationUnread = (userId) => {
      changedConversation(userId)
      const conv = conversationList.value.find((c) => c.targetUserId === userId)
      if (conv && conv.unreadCount > 0) {
        totalUnreadCount.value -= conv.unreadCount
        conv.unreadCount = 0
      }
    }

    /**
     * 设置当前聊天用户
     */
    const setCurrentChatUser = (userId) => {
      if (currentChatUserId.value !== userId) currentChatMessages.value = []
      currentChatUserId.value = userId
      clearConversationUnread(userId)
    }

    /**
     * 添加消息到当前聊天窗口
     */
    const addMessageToCurrentChat = (message) => {
      const existing = currentChatMessages.value.find((item) => item.id === message.id)
      if (existing) {
        const status = existing.examineStatus
        Object.assign(existing, message)
        if ((status === 1 || status === 2) && message.examineStatus === 0) existing.examineStatus = status
      } else {
        currentChatMessages.value.push(message)
      }
      currentChatMessages.value.sort((a, b) => new Date(a.createTime) - new Date(b.createTime) || a.id - b.id)
    }

    /**
     * 设置当前聊天消息列表
     */
    const setCurrentChatMessages = (messages) => {
      currentChatMessages.value = []
      messages.forEach(addMessageToCurrentChat)
    }

    // 同一持久化消息可能经重连、历史查询和实时通道到达，只计一次未读。
    const receiveMessage = (data, ownerId) => {
      const id = data.messageId || data.id
      if (!Number.isSafeInteger(id) || id <= 0 || data.toUserId !== ownerId) return false
      if (data.code !== undefined && data.code !== 1) return false
      if (receivedMessageIds.has(id)) return false
      receivedMessageIds.add(id)
      const message = { ...data, id, examineStatus: data.code ?? data.examineStatus }
      updateConversation(data.fromUserId, message, data.unreadCount, {
        nickname: data.fromUserNickname,
        avatar: data.fromUserAvatar,
      })
      if (currentChatUserId.value === data.fromUserId) addMessageToCurrentChat(message)
      return true
    }

    const acknowledgeMessage = (data, ownerId) => {
      const id = data.messageId || data.id
      if (!Number.isSafeInteger(id) || id <= 0 || data.fromUserId !== ownerId) return
      const message = { ...data, id, examineStatus: data.code }
      if (currentChatUserId.value === data.toUserId) addMessageToCurrentChat(message)
      if (data.code === 1) {
        updateConversation(data.toUserId, message, 0, {
          nickname: data.toUserNickname,
          avatar: data.toUserAvatar,
        })
      }
    }

    /**
     * 更新消息状态（撤回）
     */
    const revokeMessageInCurrentChat = (messageId) => {
      const message = currentChatMessages.value.find((m) => m.id === messageId)
      if (message) {
        message.isRevoked = 1
      }
    }

    /**
     * 删除会话
     */
    const removeConversation = (userId) => {
      changedConversation(userId)
      const index = conversationList.value.findIndex((conv) => conv.targetUserId === userId)
      if (index !== -1) {
        const conv = conversationList.value[index]
        totalUnreadCount.value -= conv.unreadCount
        conversationList.value.splice(index, 1)
      }
    }

    /**
     * 更新用户在线状态
     */
    const updateUserOnlineStatus = (userId, isOnline) => {
      const conv = conversationList.value.find((c) => c.targetUserId === userId)
      if (conv) {
        conv.isOnline = isOnline
      }
    }

    /**
     * 更新会话的最后一条消息内容（用于撤回消息）
     */
    const updateConversationLastMessage = (userId, content) => {
      const conv = conversationList.value.find((c) => c.targetUserId === userId)
      if (conv) {
        conv.lastMessageContent = content
      }
    }

    /**
     * 清空所有数据
     */
    const clearAll = () => {
      conversationList.value = []
      totalUnreadCount.value = 0
      currentChatUserId.value = null
      currentChatMessages.value = []
      receivedMessageIds.clear()
      conversationChanges.clear()
      conversationVersion = 0
      clearAllTyping()
    }

    /**
     * 设置当前聊天用户是否正在输入
     */
    const setTargetUserTyping = (isTyping) => {
      targetUserTyping.value = isTyping
    }

    /**
     * 设置某个会话的输入状态
     */
    const setConversationTyping = (userId, isTyping) => {
      if (isTyping) {
        conversationTypingMap.value[userId] = true
      } else {
        delete conversationTypingMap.value[userId]
      }
    }

    /**
     * 清除所有输入状态
     */
    const clearAllTyping = () => {
      targetUserTyping.value = false
      conversationTypingMap.value = {}
    }

    return {
      conversationList,
      totalUnreadCount,
      currentChatUserId,
      currentChatMessages,
      targetUserTyping,
      conversationTypingMap,
      setConversationList,
      getConversationVersion,
      updateConversation,
      clearConversationUnread,
      setCurrentChatUser,
      addMessageToCurrentChat,
      setCurrentChatMessages,
      receiveMessage,
      acknowledgeMessage,
      revokeMessageInCurrentChat,
      removeConversation,
      updateUserOnlineStatus,
      updateConversationLastMessage,
      clearAll,
      setTargetUserTyping,
      setConversationTyping,
      clearAllTyping,
    }
  },
  {
    persist: false, // 不持久化，每次刷新页面重新获取
  },
)
