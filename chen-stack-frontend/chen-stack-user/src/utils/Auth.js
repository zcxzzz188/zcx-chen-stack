// 获取token
export const GetJwt = () => {
  const jwt = localStorage.getItem('chen_stack_jwt')
  if (jwt) {
    return jwt
  }
  return null
}

// 设置token
export const SetJwt = (jwt) => {
  localStorage.setItem('chen_stack_jwt', jwt)
}

// 移除token
export const RemoveJwt = () => {
  localStorage.removeItem('chen_stack_jwt')
}
