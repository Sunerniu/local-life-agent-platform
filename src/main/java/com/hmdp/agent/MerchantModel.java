package com.hmdp.agent;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;

/** 可替换的传输边界，测试不需要真实 API Key 或付费调用。 */
public interface MerchantModel {
    ChatCompletionMessage complete(ChatCompletionCreateParams params);
}
