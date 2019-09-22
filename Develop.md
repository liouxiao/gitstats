# 开发说明

## gitlab4j-api

从github.com克隆最新的版本（4.12.5-SNAPSHOT），然后修改以下代码，重新编译并发布到本地Maven仓库。

### 1. pom.xml

版本号改为`4.12.5`

```
<version>4.12.5</version>
```

加上spring-data-mongodb的依赖：

```
        <dependency>
             <groupId>org.springframework.data</groupId>
             <artifactId>spring-data-mongodb</artifactId>
             <version>2.1.10.RELEASE</version>
        </dependency>
```

### 2. src/main/java/org/gitlab4j/api/models/AbstractUser.java

引入`Field`注解：

```
import org.springframework.data.mongodb.core.mapping.Field;
```

在state前加上Field注解：

```
    @Field(value="_state")
    private String state;
```

### 3. 编译、打包和安装

```
mvn -DskipTests package
mvn install
```


## MongoDB

### 启动MongoDB

```
docker pull mongo

docker run -d --name mongo -p 27017:27017 -p 27018:27018 -p 27019:27019 mongo
```

### 进入MongoDB

```
docker exec -it mongo bash

mongo
```

### 修正数据

有某人（同一邮箱）用了不同的AuthorName，则会被当作不同的开发者，需要在后台mongodb里修正。

假设其email为`somebody@gmail.com`，期望其用户名同一为`Somebody`，则执行：

```
db.commitStatsPo.updateMany(
      { "authorEmail":"somebody@gmail.com" },
      { $set: { "authorName" : "Somebody" } }
)

db.commitStatsPo.updateMany(
      { "authorEmail":"somebody@gmail.com" },
      { $set: { "committerName" : "Somebody" } }
)

```
