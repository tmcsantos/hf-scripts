# Move Resources

Bash script to aid in converting old ant projects into maven.
After moving all project src files into `src/main/java`,
```
cd src/main
mvresources
```
It will find all non java files and move them into resources directory, keeping the path