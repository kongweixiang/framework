/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.nio;

import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * @author kongweixiang
 * @date 2021/3/10
 * @since 1.0.0
 */
public class Excel {
    public static void main(String[] args) {

        //excel文件路径
        String excelPath = "D:\\work.xls";
        String excelResultPath = "D:\\work2.xls";

        try {
            //String encoding = "GBK";
            File excel = new File(excelPath);
            File excelResult = new File(excelResultPath);
            FileOutputStream fout = new FileOutputStream(excelResult);   //文件流对象
            if (excel.isFile() && excel.exists()) {   //判断文件是否存在

                String[] split = excel.getName().split("\\.");  //.是特殊字符，需要转义！！！！！
                Workbook wb;
                //根据文件后缀（xls/xlsx）进行判断

                if ( "xls".equals(split[1])){
                    FileInputStream fis = new FileInputStream(excel);   //文件流对象
                    wb = new HSSFWorkbook(fis);
                }else if ("xlsx".equals(split[1])){
                    wb = new XSSFWorkbook(excel);
                }else {
                    System.out.println("文件类型错误!");
                    return;
                }

                //开始解析
                Sheet sheet = wb.getSheetAt(0);     //读取sheet 0

                Sheet result = wb.createSheet("new");     //读取sheet 0


                int firstRowIndex = sheet.getFirstRowNum()+1;   //第一行是列名，所以不读
                int lastRowIndex = sheet.getLastRowNum();
                System.out.println("firstRowIndex: "+firstRowIndex);
                System.out.println("lastRowIndex: "+lastRowIndex);
                CellStyle cellStyle = wb.createCellStyle();
                cellStyle.setFillForegroundColor(HSSFColor.RED.index);//填充单元格
                cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);; //填红
                for(int rIndex = firstRowIndex; rIndex <= lastRowIndex; rIndex++) {   //遍历行
                    System.out.println("rIndex: " + rIndex);
                    Row row = sheet.getRow(rIndex);
                    Row rowResult = result.createRow(rIndex);
                    if (row != null) {
                        int firstCellIndex = row.getFirstCellNum();
                        int lastCellIndex = row.getLastCellNum();
                        for (int cIndex = firstCellIndex; cIndex < lastCellIndex; cIndex++) {   //遍历列
                            Cell cell = row.getCell(cIndex);
                            Cell cellResult = rowResult.createCell(cIndex);
                            if (cell != null) {
                                String cellStr = cell.toString();
                                System.out.print(cellStr + "-----");
                                System.out.println(change(cellStr));
                                cellResult.setCellValue(change(cellStr));
                                if(cellStr==null||"".equals(cellStr)||cellStr.length()>7){
                                    cellResult.setCellStyle(cellStyle);
                                }
                            }
                        }
                    }
                }
                wb.write(fout);
            } else {
                System.out.println("找不到指定的文件");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String change(String src){
        if(src==null||"".equals(src)||src.length()>7){
            return src;
        }
        String pre = "";
        if(src.startsWith("CX")){
            pre = "CH-25";
        }else if(src.startsWith("SX")){
            pre = "HU-25";
        }
        String z = "0000";
        src = src.substring(3);
        String desc = new StringBuffer(src.trim()).reverse().toString();
        desc = z.substring(0, z.length()-desc.length()) + desc;
//        String format = String.format(pre + "%04s", desc);
        return pre + desc;
    }
}
