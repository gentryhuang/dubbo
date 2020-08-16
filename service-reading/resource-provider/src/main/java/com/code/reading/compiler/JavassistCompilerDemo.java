package com.code.reading.compiler;

import javassist.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;


/**
 * JavassistCompilerDemo
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/14
 * <p>
 * desc：
 */
public class JavassistCompilerDemo {

    public static void main(String[] args) throws Exception {
        // 创建
        createStudentClass();
        // 读取
        readStudentClass();
    }

    /**
     * 创建字节码信息
     *
     * @throws Exception
     */
    private static void createStudentClass() throws Exception {
        // 创建Class 容器
        ClassPool pool = ClassPool.getDefault();
        CtClass ctClass = pool.makeClass("com.alibaba.dubbo.test.Student");

        // 创建属性(通用形式)
        CtField nameField = CtField.make("private String name;", ctClass);
        ctClass.addField(nameField);

        // API形式创建属性
        CtField ageField = new CtField(pool.getCtClass("int"), "age", ctClass);
        ageField.setModifiers(Modifier.PRIVATE);
        ctClass.addField(ageField);


        // 创建方法 （通用方式）
        CtMethod setName = CtMethod.make("public void setName(String name){this.name = name;}", ctClass);
        CtMethod getName = CtMethod.make("public String getName(){return name;}", ctClass);
        ctClass.addMethod(setName);
        ctClass.addMethod(getName);


        // api形式创建方法
        ctClass.addMethod(CtNewMethod.getter("getAge", ageField));
        ctClass.addMethod(CtNewMethod.setter("setAge", ageField));


        //创建无参构造方法
        CtConstructor ctConstructor = new CtConstructor(null, ctClass);
        ctConstructor.setBody("{}");
        ctClass.addConstructor(ctConstructor);

        // 创建有参构造方法
        CtConstructor constructor = new CtConstructor(new CtClass[]{CtClass.intType, pool.get("java.lang.String")}, ctClass);
        constructor.setBody("{this.age=age;this.name=name;}");
        ctClass.addConstructor(constructor);


        // api创建普通方法
        CtMethod ctMethod = new CtMethod(CtClass.voidType, "sayHello", new CtClass[]{}, ctClass);
        ctMethod.setModifiers(Modifier.PUBLIC);
        ctMethod.setBody(new StringBuilder("{\n System.out.println(\"hello world!\"); \n}").toString());
        ctClass.addMethod(ctMethod);

        // 加载class 类
        Class<?> clazz = ctClass.toClass();

        // 反射创建对象
        Object obj = clazz.newInstance();

        //方法调用
        obj.getClass().getMethod("sayHello", new Class[]{}).invoke(obj);


        // 获取ctClass的字节码
        byte[] codeByteArray = ctClass.toBytecode();

        // 将字节码写入到class文件中
        FileOutputStream fos = new FileOutputStream(new File("/Users/huanglibao/DeskTop/Student.class"));
        fos.write(codeByteArray);
        fos.close();
    }

    /**
     * 访问已存在的字节码信息
     *
     * @throws Exception
     */
    private static void readStudentClass() throws Exception {
        // 创建Class 容器
        ClassPool pool = ClassPool.getDefault();
        CtClass ctClass = pool.get("com.alibaba.dubbo.test.Student");

        //得到字节码
        byte[] bytes = ctClass.toBytecode();
        System.out.println(Arrays.toString(bytes));
        //获取类名
        System.out.println(ctClass.getName());
        //获取接口
        System.out.println(Arrays.toString(ctClass.getInterfaces()));
        //获取方法列表
        System.out.println(Arrays.toString(ctClass.getMethods()));

    }


}
