import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;


public class Index {
    //в java приняты privet, в крайнем случае protected объявления полей, инкапсуляция)
    // желательно final
    // НЕПОТОКОБЕЗОПАСНАЯ структура используется в контексте взаимодействия из потоков...
    TreeMap<String, List<Pointer>> invertedIndex;

    ExecutorService pool;

    public Index(ExecutorService pool) {
        this.pool = pool;
        //почему в конструкторе? Очень неявно
        invertedIndex = new TreeMap<>();
    }

    // InPath при передачи Path как String выглядит не очень, скорее всего ничего не мешает методу принимать уже готовый Path
    public void indexAllTxtInPath(String pathToDir) throws IOException {
        //неплохо бы сделать проверку параметра пришедшего в метод

        //неговорящее имя переменной
        Path of = Path.of(pathToDir);

        //непонятно почему выбран начальный размер 2, да ещё и как "магическое число"
        BlockingQueue<Path> files = new ArrayBlockingQueue<>(2);


        try (Stream<Path> stream = Files.list(of)) {
            //Вообще плохо - использование блокирующей очереди, при этом добавлять через add, который никак не реализует
            // возможности данного вида очередей, если необходимо чтобы поток ждал пока в очереди освободится место есть put()
            // а тут просто полетит ошибка при переполнении
            stream.forEach(files::add);
        } // я бы обрабатывал ошибку (а скорее ошибки) тут, оборачивая в кастомные ошибки приложения с описанием проблемы.
        //а не прокидывал выше

        //Не стакается запуск трёх потоков, при очереди из двух элементов
        pool.submit(new IndexTask(files));
        pool.submit(new IndexTask(files));
        pool.submit(new IndexTask(files));
    }

    public TreeMap<String, List<Pointer>> getInvertedIndex() {
        return invertedIndex;
    }

    //Это не C# и не Go) пишем методы с маленькой буквы)
    public List<Pointer> GetRelevantDocuments(String term) {
        return invertedIndex.get(term);
    }

    public Optional<Pointer> getMostRelevantDocument(String term) {
        return invertedIndex.get(term).stream().max(Comparator.comparing(o -> o.count));
    }


    //Смысл сделать класс вложенным, если он не используется сам по себе есть, а вот смысл делать его статичным - ускользает
    static class Pointer {
        private Integer count;
        private String filePath;

        public Pointer(Integer count, String filePath) {
            this.count = count;
            this.filePath = filePath;
        }

        //Смутно предчувствую проблемы с вызовом метода из статичного вложенного класса.
        @Override
        public String toString() {
            return "{" + "count=" + count + ", filePath='" + filePath + '\'' + '}';
        }
    }

    class IndexTask implements Runnable {

        private final BlockingQueue<Path> queue;

        public IndexTask(BlockingQueue<Path> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                //Не нравится подход в подборе имен переменных, хотелось бы что-то более говорящее
                Path take = queue.take();
                List<String> strings = Files.readAllLines(take);

                //крайне неприятно читается...
                strings.stream()
                        .flatMap(str -> Stream.of(str.split(" ")))
                        .forEach(word -> invertedIndex.compute(word, (k, v) -> {
                    if (v == null) return List.of(new Pointer(1, take.toString()));
                    else {
                        ArrayList<Pointer> pointers = new ArrayList<>();

                        if (v.stream().noneMatch(pointer -> pointer.filePath.equals(take.toString()))) {
                            pointers.add(new Pointer(1, take.toString()));
                        }

                        v.forEach(pointer -> {
                            if (pointer.filePath.equals(take.toString())) {
                                pointer.count = pointer.count + 1;
                            }
                        });

                        pointers.addAll(v);

                        return pointers;
                    }

                }));

            } catch (InterruptedException | IOException e) {
                throw new RuntimeException();
            }
        }
    }
}