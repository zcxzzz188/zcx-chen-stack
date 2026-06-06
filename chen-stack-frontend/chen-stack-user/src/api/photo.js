import request from '@/utils/Request'

// 上传文章图片
export function uploadArticlePhoto(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/photo/uploadArticle',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 上传专栏图片
export function uploadColumnPhoto(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/photo/uploadColumn',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 上传头像
export function uploadAvatar(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/photo/uploadAvatar',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 上传私信图片
export function uploadMessagePhoto(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/photo/uploadMessage',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 修改图片展示状态
export function changeShowStatus(data) {
  return request({
    url: 'photo/changeShowStatus',
    method: 'put',
    data,
  })
}
