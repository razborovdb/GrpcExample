package com.gitlab.techschool.pcbook.serializer;

import java.io.IOException;

import com.gitlab.techschool.pcbook.pb.Laptop;
import com.gitlab.techschool.pcbook.sample.Generator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class SerializerTest {

    @Test
    public void writeAndReadBinaryFile() throws IOException {
        String binaryFile = "laptop.bin";
        Laptop laptop1 = new Generator().NewLaptop();

        Serializer serializer = new Serializer();
        serializer.WriteBinaryFile(laptop1, binaryFile);

        Laptop laptop2 = serializer.ReadBinaryFile(binaryFile);
        assertEquals(laptop1, laptop2);
    }
}