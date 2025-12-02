package org.example;

import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.JApiClass;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class JarComparator {

    public static void compareJarfiles(File fileBefore, String versionBefore, File fileAfter, String versionAfter ){

        JApiCmpArchive beforeArchive = new JApiCmpArchive(fileBefore,versionBefore);
        JApiCmpArchive afterArchive = new JApiCmpArchive(fileAfter,versionAfter);


        JarArchiveComparatorOptions comparatorOptions = new JarArchiveComparatorOptions();
        JarArchiveComparator jarArchiveComparator = new JarArchiveComparator(comparatorOptions);
        List<JApiClass> jApiClasses = jarArchiveComparator.compare(beforeArchive,afterArchive);

        for (JApiClass c : jApiClasses){

                System.out.println(c);
            }


    }



}
