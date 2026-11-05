package com.advisory.knowledge.api.dto;

/**
 * 预签名上传目标。
 *
 * <p>{@code uploadUrl} 必须原样使用（不能附加任何参与签名的请求头），{@code objectKey} 则原封不动地回填到
 * {@link CreateDocumentRequest} 里。</p>
 *
 * @param uploadUrl        预签名 PUT 地址
 * @param objectKey        对象键，登记时必须原样带回
 * @param expiresInSeconds 地址有效期（秒）
 */
public record PresignResponse(String uploadUrl, String objectKey, long expiresInSeconds) {
}
