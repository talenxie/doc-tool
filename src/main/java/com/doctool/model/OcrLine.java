package com.doctool.model;

/**
 * OCR 识别出的一行文字及其在图像中的位置与墨迹特征，用于还原版式。
 * height 为检测框高（含留白）；inkHeight 为实际字形墨迹高度（更贴近真实字号）；
 * inkDensity 为框内墨迹密度（深色像素占比，用于判断加粗）。
 * height/inkHeight 为 0 表示信息不可用（如纯文本回退路径），按正文默认样式处理。
 */
public record OcrLine(String text, int left, int top, int width, int height,
                      int inkHeight, double inkDensity) {
}
